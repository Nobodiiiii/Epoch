package dev.epoch.bridge;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** World-scoped membership and shared ownership transitions, independent of process identity. */
public final class WorldSession implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private WorldSession(FileChannel channel, FileLock lock) {
        this.channel = channel; this.lock = lock;
    }

    public static WorldSession open(Path authority, String instance, boolean joining) throws IOException {
        if (instance == null || !instance.matches("[A-Za-z0-9_.-]{1,128}"))
            throw new IOException("Invalid world session instance");
        Path members = authority.resolve("sessions"); Files.createDirectories(members);
        try (FileChannel gate = FileChannel.open(members.resolve("gate"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = gate.lock()) {
            if (!joining) try (DirectoryStream<Path> paths = Files.newDirectoryStream(members, "*.lock")) {
                for (Path path : paths) try (FileChannel other = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    FileLock probe = tryLock(other);
                    if (probe == null) throw new IOException("This Epoch world is still open in another instance; save and quit it first");
                    probe.release();
                }
            }
            FileChannel channel = FileChannel.open(members.resolve(instance + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = tryLock(channel);
                if (lock == null) throw new IOException("This instance already has the Epoch world open");
                return new WorldSession(channel, lock);
            } catch (IOException | RuntimeException error) { channel.close(); throw error; }
        } catch (OverlappingFileLockException busy) { throw new IOException("World startup is already in progress", busy); }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try { return channel.tryLock(); } catch (OverlappingFileLockException busy) { return null; }
    }

    public static final class Startup {
        public final Path restart;
        public final ChunkStore.Lease writer;
        private Startup(Path restart, ChunkStore.Lease writer) { this.restart = restart; this.writer = writer; }
    }
    /** Must be called while the opening instance holds this world's exclusive membership. */
    public Startup initialize(WorldBinding binding, ChunkStore store, SavedWorld.Publisher saves, String instance, boolean joining) throws IOException {
        if (joining) {
            if (store.nativeBaseline() == null) throw new IOException("Source adapter has not established its native terrain boundary");
            return new Startup(null, null);
        }
        Path saved = SavedWorld.latest(binding);
        if (saved != null) { saves.restore(saved, binding, store); return new Startup(saved, null); }
        saves.initializeBaseline(store, binding);
        return new Startup(null, store.transfer(store.lease().epoch, instance));
    }

    public static void requireWriter(ChunkStore store, ChunkStore.Lease writer, String instance) throws IOException {
        ChunkStore.Lease owner = store.lease();
        if (writer == null || owner.epoch != writer.epoch || !owner.owner.equals(instance)) throw new IOException("Writer lease was revoked");
    }
    /** Native adapters prepare and light terrain after this check, before committing ownership. */
    public static long prepareResume(ChunkStore store, ChunkStore.Lease writer, StreamCheckpoint imported, String instance) throws IOException {
        long expected = imported != null ? imported.sourceEpoch : writer == null ? -1 : writer.epoch;
        String previous = imported != null ? imported.sourceInstance : instance;
        ChunkStore.Lease owner = store.lease();
        if (owner.epoch != expected || !owner.owner.equals(previous)) throw new IOException("Handoff ownership changed; refusing a second writer");
        return expected;
    }
    public static ChunkStore.Lease commitResume(ChunkStore store, long expected, String instance) throws IOException {
        return store.transfer(expected, instance, System.getProperty("epoch.transactionId", ""));
    }
    public void close(SavedWorld.Publisher saves, AuthorityReads reads) throws IOException {
        reads.close();
        try { saves.close(); } finally { close(); }
    }

    @Override public synchronized void close() throws IOException {
        try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
    }
}
