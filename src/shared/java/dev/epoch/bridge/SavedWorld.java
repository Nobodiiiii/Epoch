package dev.epoch.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

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
        StreamCheckpoint.verifySaved(snapshot, binding);
        return snapshot;
    }

    public static void publish(Snapshot snapshot, WorldBinding binding, ChunkStore.Lease lease) throws IOException {
        Path root = Paths.get(binding.authority).resolve("saved"); Files.createDirectories(root);
        String name = UUID.randomUUID() + ".json.gz";
        Path file = root.resolve(name);
        snapshot.write(file);
        StreamCheckpoint.publish(file, binding, lease);
        Path temp = Files.createTempFile(root, "current-", ".tmp");
        try {
            Files.write(temp, name.getBytes(StandardCharsets.UTF_8));
            Files.move(temp, root.resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    public static StreamCheckpoint authorize(Path snapshot, WorldBinding binding, ChunkStore store) throws IOException {
        StreamCheckpoint saved = StreamCheckpoint.verifySaved(snapshot, binding);
        ChunkStore.Lease owner = store.lease();
        saved.sourceEpoch = owner.epoch; saved.sourceInstance = owner.owner;
        return saved;
    }
}
