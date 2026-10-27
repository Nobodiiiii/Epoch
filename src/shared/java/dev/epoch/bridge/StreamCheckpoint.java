package dev.epoch.bridge;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Binds an immutable handoff snapshot to the authority owner that finished its final flush. */
public final class StreamCheckpoint {
    public int schema = 5;
    public String worldId, sourceInstance, snapshotHash;
    public String terrainRoot, nativeBase;
    public long sourceEpoch;
    private static final Gson JSON = new Gson();
    private static final Set<String> VERIFIED = Collections.newSetFromMap(new LinkedHashMap<String,Boolean>(64, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String,Boolean> entry) { return size() > 64; }
    });

    public static Path path(Path snapshot) { return snapshot.resolveSibling(snapshot.getFileName() + ".authority.json"); }
    public static StreamCheckpoint publish(Path snapshot, WorldBinding binding, ChunkStore.Lease lease) throws IOException {
        return publish(snapshot, binding, lease, new ChunkStore(Paths.get(binding.authority), binding.worldId).checkpoint(lease));
    }
    public static StreamCheckpoint publish(Path snapshot, WorldBinding binding, ChunkStore.Lease lease, String terrainRoot) throws IOException {
        StreamCheckpoint checkpoint = new StreamCheckpoint(); checkpoint.worldId = binding.worldId;
        checkpoint.sourceInstance = lease.owner; checkpoint.sourceEpoch = lease.epoch; checkpoint.snapshotHash = hash(snapshot);
        checkpoint.terrainRoot = terrainRoot;
        checkpoint.nativeBase = new ChunkStore(Paths.get(binding.authority), binding.worldId).nativeBaseline();
        if (checkpoint.nativeBase == null) throw new IOException("Missing native source terrain boundary");
        Path file = path(snapshot).toAbsolutePath(); Path temp = Files.createTempFile(file.getParent(), "checkpoint-", ".tmp");
        try {
            Files.write(temp, JSON.toJson(checkpoint).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Path pins = Paths.get(binding.authority).resolve("pins"); Files.createDirectories(pins);
            String identity = UUID.nameUUIDFromBytes(snapshot.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)).toString();
            Map<String,String> pin = new LinkedHashMap<String,String>(); pin.put("snapshot", snapshot.toAbsolutePath().normalize().toString()); pin.put("terrainRoot", terrainRoot);
            Path pinTemp = Files.createTempFile(pins, "pin-", ".tmp");
            try {
                Files.write(pinTemp, JSON.toJson(pin).getBytes(StandardCharsets.UTF_8));
                Files.move(pinTemp, pins.resolve(identity + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(pinTemp); }
        } finally { Files.deleteIfExists(temp); }
        return checkpoint;
    }
    public static StreamCheckpoint verify(Path snapshot, WorldBinding binding, ChunkStore store) throws IOException {
        StreamCheckpoint checkpoint = verifySaved(snapshot, binding);
        SavedWorld.requireBoundary(checkpoint, store);
        ChunkStore.Lease lease = store.lease();
        if (checkpoint.sourceEpoch != lease.epoch || !lease.owner.equals(checkpoint.sourceInstance))
            throw new IOException("Stale or mismatched streaming checkpoint");
        return checkpoint;
    }

    /** A saved checkpoint survives process exit. Callers must hold exclusive startup membership before rebinding it. */
    public static StreamCheckpoint verifySaved(Path snapshot, WorldBinding binding) throws IOException {
        Path file = path(snapshot);
        if (Files.size(file) > 16384) throw new IOException("Oversized streaming checkpoint");
        try {
            StreamCheckpoint checkpoint = JSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), StreamCheckpoint.class);
            if (checkpoint == null || checkpoint.schema < 3 || checkpoint.schema > 5 || !binding.worldId.equals(checkpoint.worldId)
                    || (checkpoint.schema >= 4 && (checkpoint.terrainRoot == null || !checkpoint.terrainRoot.matches("[0-9a-f-]{36}")))
                    || (checkpoint.schema == 5 && (checkpoint.nativeBase == null || !checkpoint.nativeBase.matches("[0-9a-f-]{36}")))
                    || checkpoint.sourceEpoch < 0 || checkpoint.sourceInstance == null
                    || !checkpoint.sourceInstance.matches("[A-Za-z0-9_.-]{1,128}")
                    || checkpoint.snapshotHash == null || !checkpoint.snapshotHash.matches("[0-9a-f]{64}"))
                throw new IOException("Stale or mismatched streaming checkpoint");
            String identity = snapshot.toAbsolutePath().normalize() + ":" + checkpoint.snapshotHash;
            synchronized (VERIFIED) {
                if (!VERIFIED.contains(identity)) {
                    if (!hash(snapshot).equals(checkpoint.snapshotHash)) throw new IOException("Stale or mismatched streaming checkpoint");
                    VERIFIED.add(identity);
                }
            }
            return checkpoint;
        } catch (RuntimeException bad) { throw new IOException("Malformed streaming checkpoint", bad); }
    }
    public static StreamCheckpoint verifyIntegrity(Path snapshot, WorldBinding binding) throws IOException {
        synchronized (VERIFIED) {
            String prefix = snapshot.toAbsolutePath().normalize() + ":";
            for (Iterator<String> values = VERIFIED.iterator(); values.hasNext();) if (values.next().startsWith(prefix)) values.remove();
            return verifySaved(snapshot, binding);
        }
    }
    private static String hash(Path file) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[8192]; int count;
            try (InputStream input = Files.newInputStream(file)) { while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count); }
            StringBuilder result = new StringBuilder();
            for (byte value : hash.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
