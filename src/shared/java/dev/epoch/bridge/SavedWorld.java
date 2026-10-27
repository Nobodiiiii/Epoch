package dev.epoch.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.*;
import java.util.concurrent.*;

/** Publishes an immutable restart snapshot before atomically selecting it. */
public final class SavedWorld {
    public static Path latest(WorldBinding binding) throws IOException {
        Path root = Paths.get(binding.authority).resolve("saved");
        Path pointer = root.resolve("current");
        if (!Files.exists(pointer)) return null;
        if (Files.size(pointer) > 128) throw new IOException("Invalid saved world pointer");
        String name = new String(Files.readAllBytes(pointer), StandardCharsets.UTF_8);
        if (!name.matches("[0-9a-f-]{36}\\.json\\.gz")) throw new IOException("Invalid saved world name");
        Path snapshot = root.resolve(name);
        if (!Files.isRegularFile(snapshot) || !Files.isRegularFile(StreamCheckpoint.path(snapshot))) throw new IOException("Selected checkpoint is incomplete");
        return snapshot;
    }

    public static void publish(Snapshot snapshot, WorldBinding binding, ChunkStore.Lease lease) throws IOException {
        publish(snapshot, binding, lease, new ChunkStore(Paths.get(binding.authority), binding.worldId).checkpoint(lease));
    }
    private static void publish(Snapshot snapshot, WorldBinding binding, ChunkStore.Lease lease, String terrainRoot) throws IOException {
        Path root = Paths.get(binding.authority).resolve("saved"); Files.createDirectories(root);
        String name = UUID.randomUUID() + ".json.gz";
        Path file = root.resolve(name);
        snapshot.write(file);
        StreamCheckpoint.publish(file, binding, lease, terrainRoot);
        select(root, name, file);
    }
    /** Reuse a fully written export and its admission check as the restart recovery boundary. */
    public static void adopt(Path snapshot, WorldBinding binding) throws IOException {
        Path root = Paths.get(binding.authority).resolve("saved"); Files.createDirectories(root);
        String name = UUID.randomUUID() + ".json.gz"; Path file = root.resolve(name);
        Files.copy(snapshot, file); Files.copy(StreamCheckpoint.path(snapshot), StreamCheckpoint.path(file));
        select(root, name, file);
    }
    private static void select(Path root, String name, Path file) throws IOException {
        Path temp = Files.createTempFile(root, "current-", ".tmp");
        try {
            Files.write(temp, name.getBytes(StandardCharsets.UTF_8));
            Files.move(temp, root.resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        // Retain the selected checkpoint and two previous complete checkpoints.
        List<Path> previous = new ArrayList<Path>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.json.gz")) {
            for (Path entry : files) if (!entry.equals(file) && Files.isRegularFile(StreamCheckpoint.path(entry))) previous.add(entry);
        }
        Collections.sort(previous, (a,b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()));
        for (int i = 0; i < previous.size() - 2; i++) {
            Files.deleteIfExists(StreamCheckpoint.path(previous.get(i))); Files.deleteIfExists(previous.get(i));
        }
    }

    public static StreamCheckpoint authorize(Path snapshot, WorldBinding binding, ChunkStore store) throws IOException {
        StreamCheckpoint saved = StreamCheckpoint.verifySaved(snapshot, binding);
        requireBoundary(saved, store);
        ChunkStore.Lease owner = store.lease();
        saved.sourceEpoch = owner.epoch; saved.sourceInstance = owner.owner;
        return saved;
    }

    public static void restore(Path snapshot, WorldBinding binding, ChunkStore store) throws IOException {
        StreamCheckpoint saved = StreamCheckpoint.verifySaved(snapshot, binding);
        requireBoundary(saved, store);
        store.restoreCheckpoint(saved.terrainRoot);
    }

    static void requireBoundary(StreamCheckpoint saved, ChunkStore store) throws IOException {
        if (saved.schema != 5 || saved.terrainRoot == null || saved.nativeBase == null || !saved.nativeBase.equals(store.nativeBaseline()))
            throw new IOException("Checkpoint has no matching full terrain boundary; restore a consistent full backup or explicitly migrate it before resuming");
    }

    /** One running publication and one latest pending capture per world. Never runs on the caller. */
    public static final class Publisher implements AutoCloseable {
        private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(1), task -> { Thread thread = new Thread(task, "Epoch saved checkpoint"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy());
        private volatile IOException failure;
        private Future<?> last;
        public synchronized void submit(Snapshot snapshot, WorldBinding binding, ChunkStore.Lease lease, String terrainRoot) throws IOException {
            if (failure != null) { IOException error = failure; failure = null; throw error; }
            if (worker.isShutdown()) throw new IOException("Checkpoint publisher has stopped");
            if (worker.getQueue().remainingCapacity() == 0) worker.getQueue().poll();
            last = worker.submit(() -> { try { publish(snapshot, binding, lease, terrainRoot); } catch (IOException error) { failure = error; } });
        }
        public synchronized void await() throws IOException {
            try { if (last != null) last.get(30, TimeUnit.SECONDS); }
            catch (Exception error) { if (error instanceof InterruptedException) Thread.currentThread().interrupt(); throw new IOException("Checkpoint publication failed", error); }
            if (failure != null) throw failure;
        }
        /** Handoff waits for its worker, but never compresses or hashes terrain on the server. */
        public synchronized void export(Snapshot snapshot, Path file, WorldBinding binding, ChunkStore.Lease lease, String terrainRoot) throws IOException {
            await();
            last = worker.submit(() -> {
                try { snapshot.write(file); StreamCheckpoint.publish(file, binding, lease, terrainRoot); adopt(file, binding); }
                catch (IOException error) { failure = error; }
            });
            await();
        }
        private synchronized <T> T readTask(Callable<T> operation) throws IOException {
            await();
            Future<T> reading = worker.submit(operation);
            try { return reading.get(30, TimeUnit.SECONDS); }
            catch (InterruptedException error) { reading.cancel(true); Thread.currentThread().interrupt(); throw new IOException(error); }
            catch (ExecutionException error) { throw new IOException("Checkpoint admission failed", error.getCause()); }
            catch (TimeoutException error) { reading.cancel(true); throw new IOException("Checkpoint admission timed out", error); }
        }
        public StreamCheckpoint verify(Path file, WorldBinding binding, ChunkStore store, boolean restarting) throws IOException {
            return readTask(() -> restarting ? SavedWorld.authorize(file, binding, store) : StreamCheckpoint.verify(file, binding, store));
        }
        public Snapshot read(Path file) throws IOException { return readTask(() -> Snapshot.read(file)); }
        public WorldExtras.Carry readCarry(Path file) throws IOException { return readTask(() -> WorldExtras.readCarry(file)); }
        public void writeCarry(Path file, WorldExtras.Carry carry) throws IOException {
            readTask(() -> { WorldExtras.writeCarry(file, carry); return null; });
        }
        public ChunkStore openStore(Path authority, String worldId) throws IOException { return readTask(() -> new ChunkStore(authority, worldId)); }
        public void initializeBaseline(ChunkStore store, WorldBinding binding) throws IOException {
            readTask(() -> { store.initializeNativeBaseline(binding); return null; });
        }
        public long acceptBaseline(ChunkStore store, ChunkSnapshot chunk) throws IOException {
            return readTask(() -> store.acceptNativeBaseline(chunk));
        }
        public boolean sourceContains(WorldBinding binding, ChunkKey key) throws IOException {
            return readTask(() -> {
                Path source = Paths.get(binding.sourceWorld);
                if (!Files.isDirectory(source)) throw new IOException("Original source world is unavailable");
                return NativeRegion.hasFullChunk(source, key);
            });
        }
        public long commit(ChunkStore store, ChunkStore.Lease lease, List<ChunkStore.Write> writes) throws IOException {
            return readTask(() -> store.commit(lease, writes));
        }
        public void restore(Path file, WorldBinding binding, ChunkStore store) throws IOException {
            readTask(() -> { SavedWorld.restore(file, binding, store); return null; });
        }
        @Override public void close() throws IOException {
            worker.shutdown();
            try { if (!worker.awaitTermination(30, TimeUnit.SECONDS)) throw new IOException("Checkpoint publication did not finish before shutdown"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            if (failure != null) throw failure;
        }
    }
}
