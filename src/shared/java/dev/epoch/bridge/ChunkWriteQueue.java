package dev.epoch.bridge;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Server-thread dirty tracking with one bounded background commit. Captures are made on the
 * caller's server thread; compression and disk writes happen exclusively on the worker.
 * Native unload must retain a capturable chunk or immutable snapshot until isPending(key) is false.
 */
public final class ChunkWriteQueue implements AutoCloseable {
    public interface Capture { ChunkSnapshot capture(ChunkKey key) throws Exception; }
    public interface Committed { void accept(ChunkKey key, long revision); }
    private static final class Dirty {
        long revision, generation;
        Dirty(long revision) { this.revision = revision; }
    }
    private static final class Captured {
        final ChunkKey key;
        final long generation;
        final ChunkStore.Write write;
        Captured(ChunkKey key, Dirty dirty, long generation, ChunkSnapshot chunk) {
            this.key = key; this.generation = generation;
            this.write = new ChunkStore.Write(dirty.revision, chunk);
        }
    }
    private final Thread owner = Thread.currentThread();
    private final ChunkStore store;
    private final ChunkStore.Lease lease;
    private final int capacity;
    private final Committed committed;
    private final ExecutorService worker;
    private final Map<ChunkKey, Dirty> dirty = new LinkedHashMap<ChunkKey, Dirty>();
    private final Set<ChunkKey> priority = new LinkedHashSet<ChunkKey>();
    private List<Captured> inFlight;
    private Future<Long> saving;
    private IOException failure;
    private boolean closed;

    public ChunkWriteQueue(ChunkStore store, ChunkStore.Lease lease, int capacity, Committed committed) {
        if (capacity < 1 || committed == null) throw new IllegalArgumentException("Invalid dirty queue configuration");
        this.store = store; this.lease = lease; this.capacity = capacity; this.committed = committed;
        worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Epoch chunk persistence"); thread.setDaemon(true); return thread;
        });
    }

    /** Coalesces repeated edits without capturing, converting, compressing or doing I/O. */
    public void mark(ChunkKey key, long revision) throws IOException {
        checkThread();
        if (closed || revision < 0) throw new IOException("Invalid/closed chunk write queue");
        Dirty entry = dirty.get(key);
        if (entry == null) {
            if (dirty.size() >= capacity) throw new IOException("Dirty chunk budget exhausted; stop expansion and flush");
            entry = new Dirty(revision); dirty.put(key, entry);
        }
        if (entry.generation == Long.MAX_VALUE) throw new IOException("Dirty generation exhausted");
        entry.generation++;
    }

    /** Already captured unload snapshots should release bounded memory before ordinary residents. */
    public void prioritize(ChunkKey key) {
        checkThread();
        if (dirty.containsKey(key)) priority.add(key);
    }

    public boolean isPending(ChunkKey key) { checkThread(); return dirty.containsKey(key); }
    public int pendingCount() { checkThread(); return dirty.size(); }

    /** Drain acknowledgements, then capture up to maxChunks within a soft CPU-time budget. */
    public void tick(Capture capture, int maxChunks, long budgetNanos) throws Exception {
        checkThread();
        if (closed || maxChunks < 1 || maxChunks > 128 || budgetNanos < 1) throw new IOException("Invalid capture budget");
        drain();
        if (saving != null || dirty.isEmpty()) return;
        long started = System.nanoTime();
        List<Captured> batch = new ArrayList<Captured>();
        Set<ChunkKey> order = new LinkedHashSet<ChunkKey>(priority);
        order.addAll(dirty.keySet());
        for (ChunkKey key : order) {
            Dirty entry = dirty.get(key);
            long generation = entry.generation;
            ChunkSnapshot chunk = capture.capture(key);
            if (chunk == null || !chunk.key().equals(key)) throw new IOException("Capture returned wrong chunk");
            chunk.validate(); batch.add(new Captured(key, entry, generation, chunk));
            if (batch.size() >= maxChunks || System.nanoTime() - started >= budgetNanos) break;
        }
        submit(batch);
    }

    private void submit(List<Captured> batch) {
        // Capture transfers ownership: callers must not subsequently mutate these snapshots.
        List<ChunkStore.Write> writes = new ArrayList<ChunkStore.Write>();
        for (Captured captured : batch) writes.add(captured.write);
        inFlight = batch; saving = worker.submit(() -> store.commit(lease, writes));
    }

    /** Save/unload barrier for one chunk; unrelated dirty chunks remain on the budgeted queue. */
    public void flushKey(ChunkKey key, Capture capture, long timeoutMillis) throws Exception {
        flushKeys(Collections.singletonList(key), capture, timeoutMillis);
    }

    /** Bounded barrier for explicitly selected chunks, such as already captured unload snapshots. */
    public void flushKeys(Collection<ChunkKey> keys, Capture capture, long timeoutMillis) throws Exception {
        checkThread();
        if (closed || keys == null || keys.isEmpty() || keys.size() > 128
                || timeoutMillis < 1 || timeoutMillis > 300000)
            throw new IOException("Invalid chunk flush request");
        Set<ChunkKey> selected = new LinkedHashSet<ChunkKey>(keys);
        if (selected.contains(null)) throw new IOException("Invalid chunk flush key");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        drain();
        selected.retainAll(dirty.keySet());
        while (!selected.isEmpty()) {
            if (saving == null) {
                List<Captured> batch = new ArrayList<Captured>();
                for (ChunkKey key : selected) {
                    Dirty entry = dirty.get(key);
                    long generation = entry.generation;
                    ChunkSnapshot chunk = capture.capture(key);
                    if (chunk == null || !chunk.key().equals(key)) throw new IOException("Capture returned wrong chunk");
                    chunk.validate(); batch.add(new Captured(key, entry, generation, chunk));
                }
                submit(batch);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Chunk flush timed out; retain native chunk");
            try { saving.get(remaining, TimeUnit.NANOSECONDS); }
            catch (TimeoutException timeout) { throw new IOException("Chunk flush timed out", timeout); }
            catch (ExecutionException error) { drain(); }
            drain();
            selected.retainAll(dirty.keySet());
        }
    }

    public void drain() throws IOException {
        checkThread();
        if (failure != null) throw failure;
        if (saving == null || !saving.isDone()) return;
        final long revision;
        try { revision = saving.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Interrupted chunk acknowledgement", interrupted);
        } catch (ExecutionException error) {
            failure = new IOException("Chunk persistence failed; keep native chunks and reconcile authority before retry", error.getCause());
            throw failure;
        }
        List<Captured> finished = inFlight; saving = null; inFlight = null;
        for (Captured captured : finished) {
            Dirty entry = dirty.get(captured.key);
            entry.revision = revision;
            if (entry.generation == captured.generation) { dirty.remove(captured.key); priority.remove(captured.key); }
        }
        for (Captured captured : finished) committed.accept(captured.key, revision);
    }

    /** Use only after simulation is frozen, at handoff or normal shutdown. */
    public void flush(Capture capture, long timeoutMillis) throws Exception {
        checkThread();
        if (timeoutMillis < 1 || timeoutMillis > 300000) throw new IOException("Invalid flush deadline");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!dirty.isEmpty()) {
            tick(capture, 32, TimeUnit.MILLISECONDS.toNanos(4));
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Chunk flush timed out; source must remain authoritative");
            if (saving != null) {
                try { saving.get(remaining, TimeUnit.NANOSECONDS); }
                catch (TimeoutException timeout) { throw new IOException("Chunk flush timed out", timeout); }
                catch (ExecutionException error) { drain(); }
            }
            drain();
        }
    }

    /** Never silently discard dirty data. Call flush while the world is still available first. */
    @Override public void close() throws IOException {
        checkThread(); drain();
        if (!dirty.isEmpty()) throw new IOException("Cannot close chunk writer with unsaved changes");
        closed = true; worker.shutdown();
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Chunk queue requires its server thread");
    }
}
