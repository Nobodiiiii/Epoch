package dev.epoch.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Canonical, disk-indexed chunk authority. Index operations are serialized across processes.
 * Immutable content is written before a commit journal; recovery completes publication before
 * allowing any reader. Atomic replacement is required, never emulated by delete-and-write.
 * This protects process interruption, not arbitrary filesystem/hardware corruption.
 */
public final class ChunkStore {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int CONTENT_LIMIT = 32 * 1024 * 1024;
    private static final int JOURNAL_LIMIT = 256 * 1024;
    private final Path root;
    private final String worldId;
    private final Faults faults;

    public static final class Lease {
        public final String owner;
        public final long epoch;
        private Lease(String owner, long epoch) { this.owner = owner; this.epoch = epoch; }
    }
    public static final class Versioned {
        public final long revision;
        public final ChunkSnapshot chunk;
        private Versioned(long revision, ChunkSnapshot chunk) { this.revision = revision; this.chunk = chunk; }
    }
    public static final class Write {
        public final long expectedRevision;
        public final ChunkSnapshot chunk;
        public Write(long expectedRevision, ChunkSnapshot chunk) {
            this.expectedRevision = expectedRevision; this.chunk = chunk;
        }
    }
    private static final class State {
        int schema = 3;
        String worldId, owner = "";
        long epoch, revision;
    }
    private static final class Ref {
        long revision;
        String hash;
        String originVersion;
        Ref(long revision, String hash) { this.revision = revision; this.hash = hash; }
    }
    private static final class Journal {
        State state;
        Map<String, Ref> updates = new TreeMap<String, Ref>();
    }
    interface Faults { void at(String stage) throws IOException; }
    private interface Operation<T> { T run() throws IOException; }

    public ChunkStore(Path root, String worldId) throws IOException {
        this(root, worldId, stage -> {});
    }
    ChunkStore(Path root, String worldId, Faults faults) throws IOException {
        try {
            if (!UUID.fromString(worldId).toString().equals(worldId)) throw new IllegalArgumentException();
        } catch (RuntimeException bad) { throw new IOException("World identity must be a canonical UUID", bad); }
        this.root = root.toAbsolutePath().normalize(); this.worldId = worldId; this.faults = faults;
        Files.createDirectories(this.root);
        locked(() -> {
            Path state = this.root.resolve("world.json");
            if (!Files.exists(state)) {
                State fresh = new State(); fresh.worldId = worldId; atomic(state, encode(fresh));
            }
            state(); return null;
        });
    }

    /** Coordinator only: compare-and-swap ownership after the old simulation is frozen. */
    public Lease transfer(long expectedEpoch, String owner) throws IOException {
        if (owner == null || !owner.matches("[A-Za-z0-9_.-]{1,128}")) throw new IOException("Invalid lease owner");
        return locked(() -> {
            State state = state();
            if (state.epoch != expectedEpoch || state.epoch == Long.MAX_VALUE) throw new IOException("Stale lease transfer");
            state.epoch++; state.owner = owner;
            atomic(root.resolve("world.json"), encode(state));
            return new Lease(owner, state.epoch);
        });
    }

    public Lease lease() throws IOException {
        return locked(() -> { State state = state(); return new Lease(state.owner, state.epoch); });
    }

    public Versioned read(ChunkKey key) throws IOException {
        Ref selected = locked(() -> { state(); return ref(key); });
        if (selected == null) return null;
        // Objects are immutable and currently never collected. Fix the revision under the
        // publication lock, then allow other reads/commits while reading and decoding its object.
        // Future object GC must pin readers before reclaiming any selected old revision.
        faults.at("read-selected");
        return new Versioned(selected.revision, content(key, selected));
    }

    public static void writeChunk(Path file, ChunkSnapshot chunk) throws IOException {
        chunk.validate(); byte[] raw = encode(chunk);
        if (raw.length > CONTENT_LIMIT) throw new IOException("Chunk content exceeds limit");
        atomic(file.toAbsolutePath(), compress(raw));
    }

    public static ChunkSnapshot readChunk(Path file) throws IOException {
        byte[] raw;
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(readBytes(file, CONTENT_LIMIT)))) {
            raw = bounded(input, CONTENT_LIMIT);
        }
        ChunkSnapshot chunk = decode(raw, ChunkSnapshot.class); chunk.validate(); return chunk;
    }

    /** A batch becomes visible as a unit, including recovery after interruption mid-publication. */
    public long commit(Lease lease, List<Write> writes) throws IOException {
        if (lease == null || writes == null || writes.isEmpty() || writes.size() > 128)
            throw new IOException("A commit requires 1..128 chunks and an active lease");
        List<Write> batch = new ArrayList<Write>(writes);
        Map<ChunkKey, Long> expected = new LinkedHashMap<ChunkKey, Long>();
        for (Write write : batch) {
            if (write == null || write.chunk == null || write.expectedRevision < 0)
                throw new IOException("Invalid chunk write");
            write.chunk.validate();
            if (expected.put(write.chunk.key(), write.expectedRevision) != null)
                throw new IOException("Duplicate chunk in commit");
        }
        locked(() -> validateCommit(lease, expected));
        // Stage immutable objects without holding up readers. Objects are never collected;
        // interrupted or rejected staging may leave unreferenced objects, but no visible edits.
        // As with capture ownership, callers must not mutate snapshots during this operation.
        Map<ChunkKey, String> staged = new LinkedHashMap<ChunkKey, String>();
        for (Write write : batch) {
            byte[] raw = encode(write.chunk);
            if (raw.length > CONTENT_LIMIT) throw new IOException("Chunk content exceeds limit");
            byte[] compressed = compress(raw); String hash = hash(compressed);
            if (compressed.length > CONTENT_LIMIT) throw new IOException("Compressed chunk exceeds limit");
            Path object = object(hash);
            if (!Files.exists(object)) atomic(object, compressed);
            else if (!hash(readBytes(object, CONTENT_LIMIT)).equals(hash))
                throw new IOException("Corrupt existing chunk content");
            staged.put(write.chunk.key(), hash);
        }
        faults.at("content-written");
        return locked(() -> {
            // Ownership or a chunk revision may have changed while staging. Recheck every
            // precondition before making the journal visible, and assign the revision here.
            State state = validateCommit(lease, expected);
            state.revision++;
            Journal journal = new Journal(); journal.state = state;
            for (Write write : batch) {
                Ref previous = ref(write.chunk.key());
                Ref next = new Ref(state.revision, staged.get(write.chunk.key()));
                // Preserve first-generation provenance, not the last adapter to serialize it.
                // Old references have no reliable provenance; never invent it from a later save.
                next.originVersion = previous == null ? write.chunk.version : previous.originVersion;
                journal.updates.put(write.chunk.key().toString(), next);
            }
            atomic(root.resolve("pending.json"), encode(journal));
            faults.at("committed");
            publish(journal);
            return state.revision;
        });
    }

    private State validateCommit(Lease lease, Map<ChunkKey, Long> expected) throws IOException {
        State state = state();
        if (state.owner.isEmpty() || state.epoch != lease.epoch || !state.owner.equals(lease.owner))
            throw new IOException("Stale chunk writer lease");
        if (state.revision == Long.MAX_VALUE) throw new IOException("Revision exhausted");
        for (Map.Entry<ChunkKey, Long> entry : expected.entrySet()) {
            Ref current = ref(entry.getKey());
            if ((current == null ? 0 : current.revision) != entry.getValue())
                throw new IOException("Stale chunk revision: " + entry.getKey() + "; expected=" + entry.getValue()
                        + ", actual=" + (current == null ? 0 : current.revision));
        }
        return state;
    }

    private synchronized <T> T locked(Operation<T> operation) throws IOException {
        try (FileChannel channel = FileChannel.open(root.resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            recover(); return operation.run();
        } catch (OverlappingFileLockException busy) {
            throw new IOException("Chunk authority busy in this process; serialize store tasks", busy);
        }
    }

    private void recover() throws IOException {
        Path pending = root.resolve("pending.json");
        if (!Files.exists(pending)) return;
        Journal journal = decode(readBytes(pending, JOURNAL_LIMIT), Journal.class);
        if (journal.state == null || journal.updates == null || journal.updates.isEmpty() || journal.updates.size() > 128)
            throw new IOException("Invalid chunk commit journal");
        validate(journal.state);
        State previous = state();
        if (journal.state.epoch != previous.epoch || !journal.state.owner.equals(previous.owner)
                || (journal.state.revision != previous.revision && journal.state.revision != previous.revision + 1))
            throw new IOException("Chunk commit journal does not follow current authority");
        for (Map.Entry<String, Ref> entry : journal.updates.entrySet()) {
            Ref ref = entry.getValue(); validate(ref);
            if (ref.revision != journal.state.revision) throw new IOException("Mixed commit revisions");
            content(ChunkKey.parse(entry.getKey()), ref);
        }
        publish(journal);
    }

    private void publish(Journal journal) throws IOException {
        for (Map.Entry<String, Ref> entry : journal.updates.entrySet()) {
            atomic(refPath(ChunkKey.parse(entry.getKey())), encode(entry.getValue()));
            faults.at("reference-written");
        }
        atomic(root.resolve("world.json"), encode(journal.state));
        faults.at("state-written");
        Files.delete(root.resolve("pending.json"));
    }

    private State state() throws IOException {
        State state = decode(readBytes(root.resolve("world.json"), 4096), State.class);
        validate(state); return state;
    }
    private void validate(State state) throws IOException {
        if (state.schema != 3 || !worldId.equals(state.worldId) || state.owner == null
                || (!state.owner.isEmpty() && !state.owner.matches("[A-Za-z0-9_.-]{1,128}"))
                || state.epoch < 0 || state.revision < 0)
            throw new IOException("Chunk authority identity/state mismatch");
    }
    private static void validate(Ref ref) throws IOException {
        if (ref == null || ref.revision <= 0 || ref.hash == null || !ref.hash.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid chunk reference");
        if (ref.originVersion != null && (ref.originVersion.isEmpty() || ref.originVersion.length() > 128))
            throw new IOException("Invalid chunk origin");
    }
    private Ref ref(ChunkKey key) throws IOException {
        Path path = refPath(key);
        if (!Files.exists(path)) return null;
        Ref ref = decode(readBytes(path, 1024), Ref.class); validate(ref); return ref;
    }
    private Path refPath(ChunkKey key) {
        return root.resolve("chunks").resolve(Integer.toString(Math.floorDiv(key.x, 32)))
                .resolve(Integer.toString(Math.floorDiv(key.z, 32))).resolve(key.toString() + ".json");
    }
    private Path object(String hash) { return root.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash + ".gz"); }
    private ChunkSnapshot content(ChunkKey key, Ref ref) throws IOException {
        validate(ref);
        byte[] compressed = readBytes(object(ref.hash), CONTENT_LIMIT);
        if (!hash(compressed).equals(ref.hash)) throw new IOException("Chunk checksum mismatch: " + key);
        byte[] raw;
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) { raw = bounded(input, CONTENT_LIMIT); }
        ChunkSnapshot chunk = decode(raw, ChunkSnapshot.class); chunk.validate();
        if (!chunk.key().equals(key)) throw new IOException("Chunk content coordinate mismatch");
        return chunk;
    }
    private static byte[] encode(Object value) { return JSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
    private static <T> T decode(byte[] bytes, Class<T> type) throws IOException {
        try {
            T value = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
            if (value == null) throw new IOException("Empty authority record");
            return value;
        } catch (RuntimeException bad) { throw new IOException("Malformed authority record", bad); }
    }
    private static byte[] readBytes(Path path, int limit) throws IOException {
        try (InputStream input = Files.newInputStream(path)) { return bounded(input, limit); }
    }
    private static byte[] bounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (result.size() > limit - count) throw new IOException("Authority record exceeds size limit");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }
    private static byte[] compress(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream output = new GZIPOutputStream(bytes)) { output.write(raw); }
        return bytes.toByteArray();
    }
    private static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void atomic(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "epoch-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
