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
    private static final long CACHE_LIMIT = 32L * 1024 * 1024;
    private final Map<String, Cached> cache = new LinkedHashMap<String, Cached>(16, .75f, true);
    private long cacheBytes, cacheHits, cacheMisses;
    private final Set<String> verified = Collections.newSetFromMap(new LinkedHashMap<String,Boolean>(256, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String,Boolean> entry) { return size() > 4096; }
    });
    private Set<String> discardedKeys;
    private static final class Cached {
        final ChunkSnapshot snapshot;
        final long bytes;
        Cached(ChunkSnapshot snapshot) {
            this.snapshot = snapshot;
            this.bytes = 128L + snapshot.retainedBytes();
        }
    }

    public Map<String,Object> diagnostics() {
        synchronized (cache) {
        Map<String,Object> out = new LinkedHashMap<String,Object>();
        out.put("decodedCacheHits", cacheHits); out.put("decodedCacheMisses", cacheMisses);
        out.put("decodedCacheBytes", cacheBytes); out.put("decodedCacheLimit", CACHE_LIMIT);
        return out;
        }
    }

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
        String terrainRoot;
        String nativeBase;
        String transactionId;
    }
    private static final class Ref {
        long revision;
        String hash;
        String originVersion;
        Ref(long revision, String hash) { this.revision = revision; this.hash = hash; }
    }
    private static final class Journal {
        State state;
        boolean baseline;
        Map<String, Ref> updates = new TreeMap<String, Ref>();
    }
    private interface Operation<T> { T run() throws IOException; }

    public ChunkStore(Path root, String worldId) throws IOException {
        try {
            if (!UUID.fromString(worldId).toString().equals(worldId)) throw new IllegalArgumentException();
        } catch (RuntimeException bad) { throw new IOException("World identity must be a canonical UUID", bad); }
        this.root = root.toAbsolutePath().normalize(); this.worldId = worldId;
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
        return transfer(expectedEpoch, owner, "");
    }
    public Lease transfer(long expectedEpoch, String owner, String transactionId) throws IOException {
        if (owner == null || !owner.matches("[A-Za-z0-9_.-]{1,128}")) throw new IOException("Invalid lease owner");
        if (transactionId == null || (!transactionId.isEmpty() && !transactionId.matches("[0-9a-f-]{36}"))) throw new IOException("Invalid handoff transaction");
        return locked(() -> {
            State state = state();
            if (!transactionId.isEmpty() && transactionId.equals(state.transactionId) && owner.equals(state.owner) && state.epoch == expectedEpoch + 1)
                return new Lease(owner, state.epoch);
            if (state.epoch != expectedEpoch || state.epoch == Long.MAX_VALUE) throw new IOException("Stale lease transfer");
            state.epoch++; state.owner = owner;
            state.transactionId = transactionId;
            atomic(root.resolve("world.json"), encode(state));
            return new Lease(owner, state.epoch);
        });
    }

    public Lease lease() throws IOException {
        return locked(() -> { State state = state(); return new Lease(state.owner, state.epoch); });
    }
    public String nativeBaseline() throws IOException { return locked(() -> state().nativeBase); }
    public void initializeNativeBaseline(WorldBinding binding) throws IOException {
        locked(() -> {
            State state = state();
            if (state.nativeBase == null) {
                state.nativeBase = NativeBaseline.initialize(root, Paths.get(binding.sourceWorld));
                atomic(root.resolve("world.json"), encode(state));
            }
            return null;
        });
    }
    public boolean baselineCovered(ChunkKey key) throws IOException {
        return locked(() -> new NativeBaseline(root, state().nativeBase).covered(key));
    }
    public boolean baselineRecorded(ChunkKey key) throws IOException {
        return locked(() -> new NativeBaseline(root, state().nativeBase).recorded(key));
    }
    public void markGenerated(ChunkKey key) throws IOException {
        locked(() -> {
            NativeBaseline base = new NativeBaseline(root, state().nativeBase);
            if (!base.recorded(key)) atomic(base.path(key), "{}".getBytes(StandardCharsets.UTF_8));
            return null;
        });
    }
    /** Source reader only: seal its initial native version before a chunk can be used or saved. */
    public long acceptNativeBaseline(ChunkSnapshot chunk) throws IOException {
        chunk.validate(); ChunkKey key = chunk.key();
        boolean needed = locked(() -> {
            NativeBaseline base = new NativeBaseline(root, state().nativeBase);
            return base.covered(key) && !base.recorded(key) && !Files.exists(refPath(key));
        });
        if (!needed) return revision(key);
        byte[] raw = encode(chunk); if (raw.length > CONTENT_LIMIT) throw new IOException("Baseline chunk exceeds limit");
        byte[] compressed = compress(raw); String identity = hash(compressed);
        if (!Files.exists(object(identity))) atomic(object(identity), compressed);
        else content(key, new Ref(1, identity));
        admit(identity);
        return locked(() -> {
            State state = state(); NativeBaseline base = new NativeBaseline(root, state.nativeBase);
            if (base.recorded(key) || Files.exists(refPath(key))) return ref(key) == null ? 0 : ref(key).revision;
            if (state.revision == Long.MAX_VALUE) throw new IOException("Revision exhausted");
            state.revision++; Ref initial = new Ref(state.revision, identity); initial.originVersion = chunk.version;
            Journal journal = new Journal(); journal.state = state; journal.baseline = true; journal.updates.put(key.toString(), initial);
            atomic(root.resolve("pending.json"), encode(journal)); publish(journal); return initial.revision;
        });
    }

    /** Caller has flushed native captures at a simulation boundary. Returns metadata only. */
    public String checkpoint(Lease lease) throws IOException {
        return locked(() -> {
            State state = state();
            if (lease.epoch != state.epoch || !lease.owner.equals(state.owner)) throw new IOException("Stale checkpoint writer");
            if (state.terrainRoot == null) {
                ensureIndex(state); atomic(root.resolve("world.json"), encode(state));
            }
            return state.terrainRoot;
        });
    }

    private Map<String, com.google.gson.JsonElement> references() throws IOException {
        Map<String, com.google.gson.JsonElement> result = new TreeMap<String, com.google.gson.JsonElement>();
        Path chunks = root.resolve("chunks");
        if (Files.isDirectory(chunks)) try (java.util.stream.Stream<Path> paths = Files.walk(chunks)) {
            for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".json"))::iterator) {
                String name = path.getFileName().toString(); ChunkKey key = ChunkKey.parse(name.substring(0, name.length() - 5));
                result.put(key.toString(), JSON.toJsonTree(ref(key)));
            }
        }
        return result;
    }
    private void ensureIndex(State state) throws IOException {
        if (state.terrainRoot == null) state.terrainRoot = new TerrainIndex(root).update(null, references());
    }

    /** Exclusive world startup only, before any native chunk is accepted. Crash-recoverable reindex. */
    public void restoreCheckpoint(String terrainRoot) throws IOException {
        locked(() -> {
            State state = state();
            if (terrainRoot.equals(state.terrainRoot)) return null;
            Map<String, com.google.gson.JsonElement> selected = new TerrainIndex(root).entries(terrainRoot);
            Set<String> discarded = new TreeSet<String>(references().keySet()); discarded.removeAll(selected.keySet());
            discarded.removeAll(new NativeBaseline(root, state.nativeBase).versions().keySet());
            Path discardedFile = root.resolve("discarded.json");
            if (Files.exists(discardedFile)) discarded.addAll(Arrays.asList(decode(readBytes(discardedFile, CONTENT_LIMIT), String[].class)));
            atomic(discardedFile, encode(discarded));
            atomic(root.resolve("restore.json"), encode(terrainRoot));
            restoreIndex(terrainRoot); return null;
        });
    }
    public boolean discarded(ChunkKey key) throws IOException {
        return locked(() -> {
            if (ref(key) != null) return false;
            NativeBaseline base = new NativeBaseline(root, state().nativeBase);
            if (base.recorded(key) && base.version(key) == null) return true;
            Path file = root.resolve("discarded.json");
            if (discardedKeys == null) discardedKeys = Files.exists(file)
                    ? new HashSet<String>(Arrays.asList(decode(readBytes(file, CONTENT_LIMIT), String[].class))) : Collections.<String>emptySet();
            return discardedKeys.contains(key.toString());
        });
    }
    private void restoreIndex(String terrainRoot) throws IOException {
        Map<String, com.google.gson.JsonElement> selected = new TerrainIndex(root).entries(terrainRoot);
        State state = state();
        // Deferred source references name the same pre-mutation version at every checkpoint.
        if (state.nativeBase == null) throw new IOException("Checkpoint has no native source baseline");
        for (Map.Entry<String,com.google.gson.JsonElement> entry : new NativeBaseline(root, state.nativeBase).versions().entrySet())
            if (!selected.containsKey(entry.getKey())) selected.put(entry.getKey(), entry.getValue());
        for (Map.Entry<String, com.google.gson.JsonElement> entry : selected.entrySet()) {
            Ref value = JSON.fromJson(entry.getValue(), Ref.class); validate(value);
            if (value.revision > state.revision) throw new IOException("Checkpoint reference is ahead of authority");
            if (!Files.isRegularFile(object(value.hash))) throw new IOException("Checkpoint terrain object is missing");
        }
        for (String key : references().keySet()) if (!selected.containsKey(key)) Files.deleteIfExists(refPath(ChunkKey.parse(key)));
        for (Map.Entry<String, com.google.gson.JsonElement> entry : selected.entrySet())
            atomic(refPath(ChunkKey.parse(entry.getKey())), encode(entry.getValue()));
        state.terrainRoot = new TerrainIndex(root).update(null, selected);
        atomic(root.resolve("world.json"), encode(state)); Files.delete(root.resolve("restore.json"));
        discardedKeys = null;
    }

    /** User/maintenance-initiated integrity inspection, never an implicit metadata operation. */
    public void verifyIntegrity(ChunkKey key) throws IOException {
        Ref selected = locked(() -> ref(key));
        if (selected == null) return;
        synchronized (cache) {
            if (!hash(readBytes(object(selected.hash), CONTENT_LIMIT)).equals(selected.hash)) {
                Cached removed = cache.remove(selected.hash); if (removed != null) cacheBytes -= removed.bytes;
                verified.remove(selected.hash); Files.deleteIfExists(admission(selected.hash));
                throw new IOException("Chunk checksum mismatch: " + key);
            }
            admit(selected.hash);
        }
    }

    public Versioned read(ChunkKey key) throws IOException {
        Ref selected = locked(() -> { state(); return ref(key); });
        if (selected == null) return null;
        // Offline collection holds all world membership locks. Active worlds pin every reader;
        // select under the publication lock, then decode without blocking other commits.
        return new Versioned(selected.revision, content(key, selected));
    }

    /** Publication-locked metadata lookup, without decompressing terrain. Zero means absent. */
    public long revision(ChunkKey key) throws IOException {
        Ref selected = locked(() -> { state(); return ref(key); });
        if (selected == null) return 0L;
        return selected.revision;
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
        // Stage immutable objects without holding up readers. Offline collection is excluded
        // by the caller's world membership; rejected staging leaves no visible edits.
        // As with capture ownership, callers must not mutate snapshots during this operation.
        Map<ChunkKey, String> staged = new LinkedHashMap<ChunkKey, String>();
        for (Write write : batch) {
            byte[] raw = encode(write.chunk);
            if (raw.length > CONTENT_LIMIT) throw new IOException("Chunk content exceeds limit");
            byte[] compressed = compress(raw); String hash = hash(compressed);
            if (compressed.length > CONTENT_LIMIT) throw new IOException("Compressed chunk exceeds limit");
            Path object = object(hash);
            if (!Files.exists(object)) atomic(object, compressed);
            else content(write.chunk.key(), new Ref(1, hash));
            admit(hash);
            staged.put(write.chunk.key(), hash);
        }
        return locked(() -> {
            // Ownership or a chunk revision may have changed while staging. Recheck every
            // precondition before making the journal visible, and assign the revision here.
            State state = validateCommit(lease, expected);
            ensureIndex(state);
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
            Map<String, com.google.gson.JsonElement> updates = new TreeMap<String, com.google.gson.JsonElement>();
            for (Map.Entry<String, Ref> entry : journal.updates.entrySet()) updates.put(entry.getKey(), JSON.toJsonTree(entry.getValue()));
            state.terrainRoot = new TerrainIndex(root).update(state.terrainRoot, updates);
            atomic(root.resolve("pending.json"), encode(journal));
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

    private <T> T locked(Operation<T> operation) throws IOException {
        synchronized (root.toString().intern()) {
        try (FileChannel channel = FileChannel.open(root.resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            recover(); return operation.run();
        } catch (OverlappingFileLockException busy) {
            throw new IOException("Chunk authority busy in this process; serialize store tasks", busy);
        }
        }
    }

    private void recover() throws IOException {
        Path restore = root.resolve("restore.json");
        if (Files.exists(restore)) restoreIndex(decode(readBytes(restore, 256), String.class));
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
            if (journal.baseline) {
                com.google.gson.JsonObject record = new com.google.gson.JsonObject(); record.add("ref", JSON.toJsonTree(entry.getValue()));
                atomic(new NativeBaseline(root, journal.state.nativeBase).path(ChunkKey.parse(entry.getKey())), encode(record));
            } else atomic(refPath(ChunkKey.parse(entry.getKey())), encode(entry.getValue()));
        }
        atomic(root.resolve("world.json"), encode(journal.state));
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
        if (state.terrainRoot != null && !state.terrainRoot.matches("[0-9a-f-]{36}")) throw new IOException("Invalid terrain index identity");
        if (state.nativeBase != null && !state.nativeBase.matches("[0-9a-f-]{36}")) throw new IOException("Invalid native source identity");
    }
    private static void validate(Ref ref) throws IOException {
        if (ref == null || ref.revision <= 0 || ref.hash == null || !ref.hash.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid chunk reference");
        if (ref.originVersion != null && (ref.originVersion.isEmpty() || ref.originVersion.length() > 128))
            throw new IOException("Invalid chunk origin");
    }
    private Ref ref(ChunkKey key) throws IOException {
        Path path = refPath(key);
        if (!Files.exists(path)) {
            String identity = state().nativeBase; if (identity == null) return null;
            com.google.gson.JsonElement base = new NativeBaseline(root, identity).version(key);
            if (base == null || base.isJsonNull()) return null;
            Ref inherited = JSON.fromJson(base, Ref.class); validate(inherited); return inherited;
        }
        Ref ref = decode(readBytes(path, 1024), Ref.class); validate(ref); return ref;
    }
    private Path refPath(ChunkKey key) {
        return root.resolve("chunks").resolve(Integer.toString(Math.floorDiv(key.x, 32)))
                .resolve(Integer.toString(Math.floorDiv(key.z, 32))).resolve(key.toString() + ".json");
    }
    private Path object(String hash) { return root.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash + ".gz"); }
    private Path admission(String hash) { return object(hash).resolveSibling(hash + ".accepted"); }
    private void admit(String hash) throws IOException {
        if (!Files.exists(admission(hash))) atomic(admission(hash), new byte[]{1});
        synchronized (cache) { verified.add(hash); }
    }
    private ChunkSnapshot decodeObject(ChunkKey key, byte[] compressed) throws IOException {
        byte[] raw;
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) { raw = bounded(input, CONTENT_LIMIT); }
        ChunkSnapshot chunk = decode(raw, ChunkSnapshot.class); chunk.validate();
        if (!chunk.key().equals(key)) throw new IOException("Chunk content coordinate mismatch");
        return chunk;
    }
    private ChunkSnapshot acceptObject(ChunkKey key, Ref ref, byte[] compressed) throws IOException {
        if (verified.contains(ref.hash) || Files.exists(admission(ref.hash))) return decodeObject(key, compressed);
        Path gate = object(ref.hash).resolveSibling(ref.hash + ".verification.lock");
        synchronized (gate.toString().intern()) {
            try (FileChannel channel = FileChannel.open(gate, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                // Another process may have admitted this same immutable version while we waited.
                if (Files.exists(admission(ref.hash))) return decodeObject(key, compressed);
                if (!hash(compressed).equals(ref.hash)) throw new IOException("Chunk checksum mismatch: " + key);
                ChunkSnapshot decoded = decodeObject(key, compressed); admit(ref.hash); return decoded;
            }
        }
    }
    private ChunkSnapshot content(ChunkKey key, Ref ref) throws IOException {
        validate(ref);
        synchronized (cache) {
            Cached found = cache.get(ref.hash);
            if (found != null) {
                if (!found.snapshot.key().equals(key)) throw new IOException("Chunk content coordinate mismatch");
                cacheHits++; return found.snapshot.copy();
            }
            // Serialize admission by immutable ID. Cache hits do not open the object, and eviction
            // does not invalidate a completed integrity check. Explicit maintenance handles damage.
            byte[] compressed = readBytes(object(ref.hash), CONTENT_LIMIT);
            ChunkSnapshot chunk = acceptObject(key, ref, compressed);
            verified.add(ref.hash);
            cacheMisses++;
            Cached entry = new Cached(chunk);
            if (entry.bytes <= CACHE_LIMIT) {
                Cached previous = cache.put(ref.hash, entry);
                if (previous != null) cacheBytes -= previous.bytes;
                cacheBytes += entry.bytes;
                Iterator<Cached> values = cache.values().iterator();
                while (cacheBytes > CACHE_LIMIT && values.hasNext()) { cacheBytes -= values.next().bytes; values.remove(); }
                return chunk.copy();
            }
            return chunk;
        }
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
            for (byte value : digest) { out.append("0123456789abcdef".charAt((value >>> 4) & 15)); out.append("0123456789abcdef".charAt(value & 15)); }
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
