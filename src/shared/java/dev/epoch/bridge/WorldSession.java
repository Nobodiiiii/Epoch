package dev.epoch.bridge;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Process-lifetime membership, independent of the persistent terrain writer generation. */
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

    @Override public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
