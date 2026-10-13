package dev.epoch.bridge;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercises persisted authority, stale writers and interruption at actual commit boundaries. */
public final class ChunkStoreTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String reason) {
        checks++; if (!value) throw new AssertionError(reason);
    }
    private static void rejects(Action action, String reason) throws Exception {
        checks++; try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Accepted " + reason);
    }
    private static ChunkSnapshot chunk(int x, int z, String state) {
        ChunkSnapshot chunk = new ChunkSnapshot(); chunk.chunkX = x; chunk.chunkZ = z; chunk.version = "1.20.1";
        chunk.palette.add(state); chunk.runs = new int[]{0, ChunkSnapshot.VOLUME};
        String[] biomes = new String[ChunkSnapshot.VOLUME / 4]; Arrays.fill(biomes, "minecraft:plains");
        chunk.biomes = WorldExtras.Biomes.of(biomes); return chunk;
    }
    private static List<ChunkStore.Write> writes(long revision, ChunkSnapshot... chunks) {
        List<ChunkStore.Write> out = new ArrayList<ChunkStore.Write>();
        for (ChunkSnapshot chunk : chunks) out.add(new ChunkStore.Write(revision, chunk)); return out;
    }
    private static void coordinates() throws Exception {
        check(ChunkKey.at(-.01, -16.01).equals(new ChunkKey(-1, -2)), "Negative coordinates truncated");
        check(ChunkKey.at(-16, 16).equals(new ChunkKey(-1, 1)), "Exact chunk boundary wrong");
        check(ChunkKey.at(29999999.9, -30000000).equals(new ChunkKey(1874999, -1875000)), "World limits wrong");
        rejects(() -> ChunkKey.parse("../0,0"), "path traversal key");
        rejects(() -> ChunkKey.parse("01,0"), "noncanonical key");
        rejects(() -> ChunkKey.parse("1875000,0"), "out-of-bounds chunk");
    }
    private static void migration() throws Exception {
        Snapshot s = new Snapshot(); s.schema = 2; s.version = "1.20.1";
        s.x0 = -32; s.z0 = 16; s.sizeX = 32; s.sizeZ = 16;
        s.palette.add("{Name:\"minecraft:air\"}"); s.palette.add("{Name:\"minecraft:chest\"}");
        int sourceIndex = (64 * 16 + 3) * 32 + 17;
        s.runs = new int[]{0, sourceIndex, 1, 1, 0, 32 * 16 * 256 - sourceIndex - 1};
        String payload = "{id:\"minecraft:chest\",x:-15,y:64,z:19,Items:[],Exact:9223372036854775807L}";
        s.blockEntities.put(Integer.toString(sourceIndex), payload);
        s.extras = new WorldExtras(); s.extras.weather = new WorldExtras.Weather(); s.extras.player = "{}";
        String[] biomeCells = new String[32 * 16 * 64]; Arrays.fill(biomeCells, "minecraft:plains");
        biomeCells[(20 * 16 + 3) * 32 + 17] = "minecraft:lush_caves";
        s.extras.biomes = WorldExtras.Biomes.of(biomeCells);
        List<ChunkSnapshot> result = ChunkSnapshot.split(s);
        check(result.size() == 2, "Wrong migrated chunk count");
        check(result.get(0).key().equals(new ChunkKey(-2, 1)), "Wrong first chunk coordinate");
        check(result.get(1).key().equals(new ChunkKey(-1, 1)), "Wrong second chunk coordinate");
        check(result.get(1).blockEntities.get(Integer.toString((64 * 16 + 3) * 16 + 1)).equals(payload), "NBT altered or incorrectly indexed");
        check(result.get(0).blockEntities.isEmpty(), "NBT leaked across chunks");
        check(result.get(0).runs.length == 2 && result.get(0).runs[1] == 65536, "Air lost on migration");
        check(result.get(1).biomes.expand(16384)[(20 * 16 + 3) * 16 + 1].equals("minecraft:lush_caves"), "3D biome index wrong");
        WorldExtras.Carry carry = new WorldExtras.Carry(); carry.bounds(s); carry.original = s.extras.biomes;
        Arrays.fill(biomeCells, "minecraft:plains"); carry.applied = WorldExtras.Biomes.of(biomeCells);
        WorldExtras.Carry cropped = carry.chunk(new ChunkKey(-1, 1));
        String[] nativeCells = new String[16384]; Arrays.fill(nativeCells, "minecraft:plains");
        check(cropped.restore(result.get(1).blocks(), nativeCells)[(20 * 16 + 3) * 16 + 1].equals("minecraft:lush_caves"),
                "Adopting old environment carry lost underground biome detail");
        check(carry.chunk(new ChunkKey(20, 20)) == null, "Legacy biome carry leaked outside its original region");
        s.schema = 1; s.extras = null;
        rejects(() -> ChunkSnapshot.split(s), "schema 1 without captured biome evidence");
    }
    private static void columns() throws Exception {
        Snapshot remote = chunk(-100, 300, "air").blocks(); remote.validateColumns();
        check(remote.blockX(0) == -1600 && remote.blockZ(0) == 4800 && remote.blockY(0) == 0,
                "Remote chunk begins at wrong world coordinate");
        check(remote.blockX(65535) == -1585 && remote.blockZ(65535) == 4815 && remote.blockY(65535) == 255,
                "Remote chunk ends at wrong world coordinate");
        check(remote.minChunkX() == -100 && remote.maxChunkX() == -100 && remote.minChunkZ() == 300
                && remote.maxChunkZ() == 300, "Remote light/network chunk bounds incorrect");
        remote.x0 = Integer.MAX_VALUE - 15;
        rejects(remote::validateColumns, "overflowing terrain origin");
        remote.x0 = -1599;
        rejects(remote::validateColumns, "unaligned terrain import");
    }
    private static void binding(Path root) throws Exception {
        Path source = root.resolve("source"); Files.createDirectories(source);
        WorldBinding first = WorldBinding.open(source, root.resolve("authority"), "1.20.1", "source-instance");
        WorldBinding again = WorldBinding.open(source, root.resolve("different-default"), "1.20.1", "source-instance");
        check(first.worldId.equals(again.worldId) && first.authority.equals(again.authority), "Reopening changed world identity or authority");
        Path target = root.resolve("target/epoch/authority.json"); first.write(target);
        check(WorldBinding.read(target).sourceWorld.equals(source.toRealPath().toString()), "Target lost original source binding");
        Path second = root.resolve("unrelated"); Files.createDirectories(second);
        check(!WorldBinding.open(second, root.resolve("authority"), "1.20.1", "source-instance").worldId.equals(first.worldId),
                "Two saves in the same instance share a world identity");
        first.authority = "relative-path";
        rejects(() -> first.write(target), "relative authority binding");
        check(WorldBinding.read(target).worldId.equals(again.worldId), "Rejected binding replaced valid target identity");
    }
    private static void checkpoint(Path root) throws Exception {
        Path world = root.resolve("world"); Files.createDirectories(world);
        WorldBinding binding = WorldBinding.open(world, root.resolve("authorities"), "1.20.1", "source");
        ChunkStore store = new ChunkStore(Paths.get(binding.authority), binding.worldId);
        ChunkStore.Lease lease = store.transfer(0, "source");
        Path snapshot = root.resolve("snapshot.gz"); chunk(1, 2, "air").blocks().write(snapshot);
        StreamCheckpoint.publish(snapshot, binding, lease);
        check(StreamCheckpoint.verify(snapshot, binding, store).sourceEpoch == lease.epoch, "Checkpoint lost writer generation");
        chunk(1, 2, "changed").blocks().write(snapshot);
        rejects(() -> StreamCheckpoint.verify(snapshot, binding, store), "old header attached to new snapshot bytes");
        StreamCheckpoint.publish(snapshot, binding, lease); store.transfer(lease.epoch, "target");
        rejects(() -> StreamCheckpoint.verify(snapshot, binding, store), "checkpoint after ownership changed");
    }
    private static void sourceRequests(Path root) throws Exception {
        Files.createDirectories(root); String world = UUID.randomUUID().toString(); ChunkKey key = new ChunkKey(-500, 800);
        ExecutorService requester = Executors.newSingleThreadExecutor();
        try {
            Future<ChunkSnapshot> response = requester.submit(() -> SourceChunkRequests.fetch(root, world, key, 5000));
            List<SourceChunkRequests.Request> requests = Collections.emptyList(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (requests.isEmpty() && System.nanoTime() < deadline) { requests = SourceChunkRequests.poll(root, world); Thread.sleep(1); }
            check(requests.size() == 1 && requests.get(0).key().equals(key), "Source request lost remote coordinates");
            ChunkSnapshot original = chunk(key.x, key.z, "old-version-state"); original.blockEntities.put("3", "{Exact:9223372036854775807L}");
            SourceChunkRequests.respond(root, requests.get(0), original);
            check(response.get(5, TimeUnit.SECONDS).blockEntities.equals(original.blockEntities), "Source response changed opaque NBT");
            rejects(() -> SourceChunkRequests.fetch(root, world, key, 10), "unavailable source must not generate replacement terrain");
            try (java.util.stream.Stream<Path> files = Files.list(root)) { check(files.count() == 0, "Completed/timed-out source requests were not cleaned up"); }
        } finally { requester.shutdownNow(); }
    }
    private static void authority(Path root) throws Exception {
        String id = UUID.randomUUID().toString(); ChunkStore store = new ChunkStore(root, id);
        ChunkStore.Lease a = store.transfer(0, "source");
        ChunkSnapshot first = chunk(-100, 300, "stone");
        first.blockEntities.put("1", "{Original:{Huge:9223372036854775807L},Text:\"未知方块\"}");
        long r1 = store.commit(a, writes(0, first));
        check(r1 == 1, "First revision wrong");
        check(store.read(first.key()).chunk.blockEntities.equals(first.blockEntities), "Opaque NBT changed");
        check(store.read(new ChunkKey(10, 20)) == null, "Missing chunk interpreted as air");
        rejects(() -> store.commit(a, writes(0, first)), "stale revision");
        ChunkStore.Lease b = store.transfer(a.epoch, "target");
        rejects(() -> store.commit(a, writes(r1, first)), "old instance writer");
        rejects(() -> store.transfer(a.epoch, "third"), "stale ownership transfer");
        ChunkSnapshot deleted = chunk(-100, 300, "air"); deleted.version = "1.12.2";
        long r2 = store.commit(b, writes(r1, deleted));
        ChunkStore reopened = new ChunkStore(root, id);
        check(reopened.read(deleted.key()).revision == r2, "Revision lost on reopen");
        check(reopened.read(deleted.key()).chunk.blockEntities.isEmpty(), "Deleted payload resurrected");
        check(reopened.read(deleted.key()).chunk.palette.get(0).equals("air"), "Air deletion lost");
        rejects(() -> new ChunkStore(root, UUID.randomUUID().toString()), "different world UUID");
        rejects(() -> store.commit(b, writes(r2, deleted, deleted)), "duplicate chunk batch");
        ChunkSnapshot valid = chunk(0, 0, "stone"), invalid = chunk(1, 0, "stone"); invalid.runs[1]--;
        rejects(() -> store.commit(b, writes(0, valid, invalid)), "incomplete chunk");
        check(store.read(valid.key()) == null, "Rejected batch partially published");
        try (java.util.stream.Stream<Path> paths = Files.walk(root.resolve("objects"))) {
            // Corrupt all immutable bodies to ensure the current one is included.
            for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".gz"))::iterator)
                Files.write(path, new byte[]{1, 2, 3});
        }
        rejects(() -> store.read(deleted.key()), "corrupt immutable content");
    }
    private static void writeQueue(Path root) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        ChunkStore store = new ChunkStore(root, UUID.randomUUID().toString(), stage -> {
            if (stage.equals("content-written") && first.getAndSet(false)) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Test latch timed out"); }
                catch (InterruptedException interrupted) { throw new IOException(interrupted); }
            }
        });
        ChunkStore.Lease lease = store.transfer(0, "source");
        List<Long> acknowledgements = new ArrayList<Long>();
        ChunkWriteQueue queue = new ChunkWriteQueue(store, lease, 2, (key, revision) -> acknowledgements.add(revision));
        ChunkKey key = new ChunkKey(1000, -2000);
        AtomicInteger captures = new AtomicInteger();
        AtomicReference<String> state = new AtomicReference<String>("old");
        Thread owner = Thread.currentThread();
        ChunkWriteQueue.Capture capture = requested -> {
            check(Thread.currentThread() == owner, "Native capture left the server thread");
            captures.incrementAndGet(); return chunk(requested.x, requested.z, state.get());
        };
        try {
            for (int n = 0; n < 10000; n++) queue.mark(key, 0);
            check(queue.pendingCount() == 1 && captures.get() == 0, "Per-edit capture/I/O instead of coalescing");
            queue.tick(capture, 1, TimeUnit.MILLISECONDS.toNanos(4));
            check(entered.await(5, TimeUnit.SECONDS), "Background commit did not start");
            check(queue.isPending(key), "Unload allowed before durable acknowledgement");
            queue.flushKey(new ChunkKey(8, 9), capture, 10);
            check(captures.get() == 1, "Saving a clean chunk waited for unrelated persistence");
            state.set("new"); queue.mark(key, 0);
            // A source freeze cannot declare success while persistence is still blocked.
            rejects(() -> queue.flush(capture, 10), "incomplete handoff flush");
            rejects(() -> queue.flushKey(key, capture, 10), "incomplete unload flush");
            check(queue.isPending(key), "Timeout discarded the dirty chunk");
        } finally { release.countDown(); }
        queue.flushKey(key, capture, 10000);
        check(captures.get() == 2, "An edit during commit was lost or repeated unnecessarily");
        check(acknowledgements.equals(Arrays.asList(1L, 2L)), "Commit acknowledgements out of order");
        check(store.read(key).chunk.palette.get(0).equals("new"), "Latest edit missing after final flush");
        check(!queue.isPending(key), "Unload still blocked after final commit");
        queue.mark(new ChunkKey(0, 0), 0); queue.mark(new ChunkKey(1, 0), 0);
        rejects(() -> queue.mark(new ChunkKey(2, 0), 0), "unbounded dirty queue growth");
        rejects(queue::close, "closing with unsaved native chunks");
        queue.prioritize(new ChunkKey(1, 0));
        queue.prioritize(new ChunkKey(9, 9)); // Absent keys must not create work.
        queue.tick(capture, 1, TimeUnit.MILLISECONDS.toNanos(4));
        queue.flushKey(new ChunkKey(1, 0), capture, 10000);
        check(store.read(new ChunkKey(1, 0)) != null, "Selected unload chunk was not persisted");
        check(queue.isPending(new ChunkKey(0, 0)) && store.read(new ChunkKey(0, 0)) == null,
                "Single chunk save persisted unrelated queued chunks");
        queue.flush(capture, 10000); queue.close();
        rejects(() -> queue.mark(key, 2), "write after queue shutdown");
    }
    private static void selectedFlush(Path root) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        ChunkStore store = new ChunkStore(root, UUID.randomUUID().toString(), stage -> {
            if (stage.equals("content-written") && pause.getAndSet(false)) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Batch test timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
        });
        ChunkStore.Lease lease = store.transfer(0, "source");
        Map<ChunkKey, Long> acknowledgements = new HashMap<ChunkKey, Long>();
        ChunkWriteQueue queue = new ChunkWriteQueue(store, lease, 32, acknowledgements::put);
        List<ChunkKey> selected = new ArrayList<ChunkKey>();
        Map<ChunkKey, Integer> captures = new HashMap<ChunkKey, Integer>();
        for (int i = 0; i < 17; i++) {
            ChunkKey key = new ChunkKey(i, 0); queue.mark(key, 0);
            if (i < 16) selected.add(key);
        }
        ChunkKey unrelated = new ChunkKey(16, 0), edited = selected.get(0);
        AtomicReference<String> value = new AtomicReference<String>("old");
        ChunkWriteQueue.Capture capture = key -> {
            captures.put(key, captures.getOrDefault(key, 0) + 1);
            return chunk(key.x, key.z, value.get());
        };
        try {
            rejects(() -> queue.flushKeys(selected, capture, 10), "unfinished selected batch");
            check(entered.await(5, TimeUnit.SECONDS), "Selected batch did not reach the worker");
            check(queue.pendingCount() == 17 && acknowledgements.isEmpty(), "Timeout discarded selected data");
            check(captures.size() == 16 && !captures.containsKey(unrelated), "Batch captured unrelated terrain");
            value.set("new"); queue.mark(edited, 0);
        } finally { release.countDown(); }
        queue.flushKeys(selected, capture, 10000);
        check(queue.pendingCount() == 1 && queue.isPending(unrelated), "Selected flush drained unrelated edits");
        check(store.read(unrelated) == null, "Unselected terrain was persisted");
        long common = store.read(selected.get(1)).revision;
        for (int i = 1; i < selected.size(); i++)
            check(store.read(selected.get(i)).revision == common && captures.get(selected.get(i)) == 1,
                    "Selected snapshots were committed individually or captured again");
        check(store.read(edited).revision > common && store.read(edited).chunk.palette.get(0).equals("new")
                && captures.get(edited) == 2, "Edit during selected commit was lost");
        queue.flush(capture, 10000); queue.close();
    }
    private static void concurrentRead(Path root) throws Exception {
        CountDownLatch selected = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        ChunkStore store = new ChunkStore(root, UUID.randomUUID().toString(), stage -> {
            if (stage.equals("read-selected") && pause.getAndSet(false)) {
                selected.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Read test latch timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
        });
        ChunkStore.Lease lease = store.transfer(0, "source");
        ChunkSnapshot old = chunk(0, 0, "old");
        long first = store.commit(lease, writes(0, old));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<ChunkStore.Versioned> reading = threads.submit(() -> store.read(old.key()));
            check(selected.await(5, TimeUnit.SECONDS), "Reader did not select its revision");
            Future<Long> writing = threads.submit(() -> store.commit(lease, writes(first, chunk(0, 0, "new"))));
            long second = writing.get(5, TimeUnit.SECONDS);
            check(second > first && !reading.isDone(), "Content reading blocked publication");
            release.countDown();
            ChunkStore.Versioned before = reading.get(5, TimeUnit.SECONDS);
            check(before.revision == first && before.chunk.palette.get(0).equals("old"),
                    "Reader mixed a selected revision with newer content");
            ChunkStore.Versioned after = store.read(old.key());
            check(after.revision == second && after.chunk.palette.get(0).equals("new"),
                    "Subsequent read did not observe published content");
        } finally {
            release.countDown(); threads.shutdownNow();
            if (!threads.awaitTermination(10, TimeUnit.SECONDS)) throw new IOException("Read test threads did not stop");
        }
    }
    private static void stagedCommit(Path root, String race) throws Exception {
        CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(false);
        ChunkStore store = new ChunkStore(root, UUID.randomUUID().toString(), stage -> {
            if (stage.equals("content-written") && pause.getAndSet(false)) {
                staged.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Staging test timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
        });
        ChunkStore.Lease lease = store.transfer(0, "source");
        ChunkKey a = new ChunkKey(0, 0), b = new ChunkKey(1, 0);
        long first = store.commit(lease, writes(0, chunk(0, 0, "old"), chunk(1, 0, "old")));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            pause.set(true);
            Future<Long> writing = threads.submit(() -> store.commit(lease,
                    writes(first, chunk(0, 0, "staged"), chunk(1, 0, "staged"))));
            check(staged.await(5, TimeUnit.SECONDS), "Commit did not stage content");
            Future<Long> competing = threads.submit(() -> {
                ChunkStore.Versioned old = store.read(a);
                if (old.revision != first || !old.chunk.palette.get(0).equals("old"))
                    throw new IOException("Staged content became visible before publication");
                if (race.equals("lease")) return store.transfer(lease.epoch, "target").epoch;
                return store.commit(lease, race.equals("revision") ? writes(first, chunk(0, 0, "winner"))
                        : writes(0, chunk(2, 0, "unrelated")));
            });
            long competingResult = competing.get(5, TimeUnit.SECONDS);
            check(!writing.isDone(), "Staging did not remain paused while readers/other writers progressed");
            release.countDown();
            if (race.equals("unrelated")) {
                long last = writing.get(5, TimeUnit.SECONDS);
                check(last > competingResult, "Staging reserved an obsolete global revision");
                check(store.read(a).revision == last && store.read(b).revision == last,
                        "Staged batch lost atomic revision after unrelated commit");
                check(store.read(a).chunk.palette.get(0).equals("staged")
                        && store.read(b).chunk.palette.get(0).equals("staged"), "Staged contents missing");
            } else {
                try { writing.get(5, TimeUnit.SECONDS); throw new AssertionError("Stale staged commit published: " + race); }
                catch (ExecutionException error) {
                    check(error.getCause() instanceof IOException && error.getCause().getMessage().contains(
                            race.equals("lease") ? "Stale chunk writer lease" : "Stale chunk revision"),
                            "Wrong staged commit rejection: " + error.getCause());
                }
                check(store.read(a).chunk.palette.get(0).equals(race.equals("lease") ? "old" : "winner"),
                        "Stale staging replaced current content");
                check(store.read(b).revision == first && store.read(b).chunk.palette.get(0).equals("old"),
                        "Rejected staging partially published its other chunk");
            }
            check(!Files.exists(root.resolve("pending.json")), "Staging race left a pending journal");
        } finally {
            release.countDown(); threads.shutdownNow();
            if (!threads.awaitTermination(10, TimeUnit.SECONDS)) throw new IOException("Staging test threads did not stop");
        }
    }
    private static void interrupted(Path root, String stage) throws Exception {
        String id = UUID.randomUUID().toString(); ChunkStore initial = new ChunkStore(root, id);
        ChunkStore.Lease lease = initial.transfer(0, "source");
        ChunkSnapshot a = chunk(0, 0, "old"), b = chunk(1, 0, "old");
        initial.commit(lease, writes(0, a, b));
        ChunkStore failing = new ChunkStore(root, id, point -> { if (point.equals(stage)) throw new IOException("Injected interruption"); });
        a = chunk(0, 0, "new"); b = chunk(1, 0, "new");
        final List<ChunkStore.Write> batch = writes(1, a, b);
        rejects(() -> failing.commit(lease, batch), "uninterrupted injected failure");
        ChunkStore recovered = new ChunkStore(root, id);
        boolean committed = !stage.equals("content-written");
        for (ChunkKey key : Arrays.asList(new ChunkKey(0, 0), new ChunkKey(1, 0))) {
            ChunkStore.Versioned got = recovered.read(key);
            check(got.revision == (committed ? 2 : 1), "Partial revision after " + stage);
            check(got.chunk.palette.get(0).equals(committed ? "new" : "old"), "Partial content after " + stage);
        }
        check(!Files.exists(root.resolve("pending.json")), "Journal not recovered after " + stage);
        check(recovered.lease().epoch == lease.epoch, "Recovery changed writer lease");
    }
    private static void origins(Path root) throws Exception {
        Path world=root.resolve("world");Files.createDirectories(world);
        WorldBinding binding=WorldBinding.open(world,root.resolve("authorities"),"1.20.1","source");
        Path authority=Paths.get(binding.authority),bindingFile=world.resolve("epoch/authority.json");
        ChunkStore store=new ChunkStore(authority,binding.worldId);
        ChunkStore.Lease lease=store.transfer(0,"source");
        long first=store.commit(lease,writes(0,chunk(-2,-1,"air"),chunk(-1,-1,"air")));
        ChunkSnapshot edited=chunk(-2,-1,"stone");edited.version="1.7.10";
        store.commit(lease,writes(first,edited));
        ChunkOrigins.View view=ChunkOrigins.read(bindingFile,-2,-1,-1,-1);
        check(view.chunks==2&&view.areas.size()==1,"Origin runs did not merge across negative coordinates");
        check(view.areas.get(0).version.equals("1.20.1")&&view.areas.get(0).x0==-2&&view.areas.get(0).x1==0,
                "Saving in another adapter changed generation provenance");
        Path oldRef=authority.resolve("chunks/-1/-1/-1,-1.json");
        com.google.gson.JsonObject legacy=new com.google.gson.JsonParser().parse(new String(Files.readAllBytes(oldRef),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        legacy.remove("originVersion");Files.write(oldRef,legacy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ChunkSnapshot legacyEdit=chunk(-1,-1,"stone");legacyEdit.version="1.12.2";
        store.commit(lease,writes(first,legacyEdit));
        view=ChunkOrigins.read(bindingFile,-2,-1,-1,-1);
        check(view.areas.size()==2&&view.areas.get(1).version.equals(ChunkOrigins.UNKNOWN),"Legacy provenance was fabricated");
        check(ChunkOrigins.read(bindingFile,10,10,12,12).chunks==0,"Origins leaked outside viewport");
        check(ChunkOrigins.read(bindingFile,-10000,-10000,10000,10000).limited,"Zoomed-out scan was unbounded");
    }

    private static void cache(Path root) throws Exception {
        ChunkStore store = new ChunkStore(root, UUID.randomUUID().toString());
        ChunkStore.Lease lease = store.transfer(0, "cache-test");
        ChunkSnapshot original = chunk(2, -3, "stone");
        original.appearanceSources.put("1", "original-instance");
        long revision = store.commit(lease, writes(0, original));
        ChunkKey key = original.key();
        ChunkSnapshot first = store.read(key).chunk;
        first.palette.set(0, "air"); first.runs[1] = 1;
        first.biomes.palette.set(0, "minecraft:desert"); first.biomes.runs[1] = 1;
        first.appearanceSources.clear(); first.blockEntities.put("1", "bad");
        ChunkSnapshot second = store.read(key).chunk;
        check(second.palette.get(0).equals("stone") && second.runs[1] == 65536, "Cache leaked caller array/list mutation");
        check(second.biomes.palette.get(0).equals("minecraft:plains") && second.biomes.runs[1] == 16384, "Cache leaked biome mutation");
        check(second.blockEntities.isEmpty() && second.appearanceSources.size() == 1, "Cache leaked map mutation");
        check(((Number)store.diagnostics().get("decodedCacheHits")).longValue() > 0, "Repeated reads did not reuse decoded content");
        check(store.revision(key) == revision, "Metadata revision differs from snapshot");
        check(ProjectionCache.reusable("world", "world", revision, revision, "rules", "rules"), "Clean native cache rejected");
        check(!ProjectionCache.reusable("world", "other", revision, revision, "rules", "rules"), "Cross-world cache accepted");
        check(!ProjectionCache.reusable("world", "world", revision, revision + 1, "rules", "rules"), "Stale cache accepted");
        check(!ProjectionCache.reusable("world", "world", revision, revision, null, "rules"), "Dirty cache accepted");
        check(!ProjectionCache.reusable("world", "world", revision, revision, "old-rules", "new-rules"), "Rule change did not invalidate cache");
        Path object;
        try (java.util.stream.Stream<Path> objects = Files.walk(root.resolve("objects"))) {
            object = objects.filter(Files::isRegularFile).findFirst().get();
        }
        java.nio.file.attribute.FileTime time = Files.getLastModifiedTime(object);
        byte[] bytes = Files.readAllBytes(object); bytes[bytes.length / 2] ^= 1;
        Files.write(object, bytes); Files.setLastModifiedTime(object, time);
        rejects(() -> store.read(key), "cached object corrupted without changing size or timestamp");
        rejects(() -> store.revision(key), "native cache bypassed corrupt authority object");
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("epoch-chunk-store-tests-");
        try {
            coordinates(); columns(); binding(root.resolve("binding")); checkpoint(root.resolve("checkpoint")); sourceRequests(root.resolve("requests"));
            origins(root.resolve("origins"));
            cache(root.resolve("cache"));
            migration(); authority(root.resolve("authority")); writeQueue(root.resolve("queue"));
            concurrentRead(root.resolve("concurrent-read"));
            selectedFlush(root.resolve("selected-flush"));
            for (String race : Arrays.asList("lease", "revision", "unrelated"))
                stagedCommit(root.resolve("staged-" + race), race);
            for (String stage : Arrays.asList("content-written", "committed", "reference-written", "state-written"))
                interrupted(root.resolve(stage), stage);
            System.out.println("ChunkStoreTest: " + checks + " checks passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
