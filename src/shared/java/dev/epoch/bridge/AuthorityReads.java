package dev.epoch.bridge;

import java.util.concurrent.*;
import java.util.*;
import java.util.function.Function;

/** Small, fixed authority I/O budget. Rejection completes dependent futures exceptionally. */
public final class AuthorityReads implements AutoCloseable {
    private final Map<ChunkKey, CompletableFuture<?>> pending = new HashMap<ChunkKey, CompletableFuture<?>>();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(128),
                task -> { Thread thread = new Thread(task, "Epoch authority reads"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy());
    private boolean closed;

    public ChunkStore.Versioned read(ChunkStore store, ChunkKey key) throws Exception {
        return this.<Void,ChunkStore.Versioned>submit(key, CompletableFuture.completedFuture(null), ignored -> {
            try { return store.read(key); } catch (java.io.IOException error) { throw new CompletionException(error); }
        }).get(30, TimeUnit.SECONDS);
    }

    /** Admission is bounded before waiting for native I/O; duplicate chunk requests share work. */
    @SuppressWarnings("unchecked")
    public synchronized <N,T> CompletableFuture<T> submit(ChunkKey key, CompletableFuture<N> nativeRead, Function<N,T> conversion) {
        CompletableFuture<T> existing = (CompletableFuture<T>)pending.get(key);
        if (existing != null) return existing;
        CompletableFuture<T> result = new CompletableFuture<T>();
        if (closed || pending.size() >= 128) {
            result.completeExceptionally(new RejectedExecutionException("Authority read capacity exhausted; stop chunk expansion and retry"));
            return result;
        }
        pending.put(key, result);
        result.whenComplete((value,error) -> { synchronized (AuthorityReads.this) { pending.remove(key, result); } });
        nativeRead.whenComplete((value,error) -> {
            if (result.isDone()) return;
            if (error != null) { result.completeExceptionally(error); return; }
            try { worker.execute(() -> {
                if (result.isDone()) return;
                try { result.complete(conversion.apply(value)); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            }); } catch (RejectedExecutionException full) { result.completeExceptionally(full); }
        });
        return result;
    }

    @Override public synchronized void close() {
        closed = true;
        for (CompletableFuture<?> task : new ArrayList<CompletableFuture<?>>(pending.values()))
            task.completeExceptionally(new CancellationException("World session closed"));
        pending.clear(); worker.shutdownNow();
    }
}
