package dev.epoch;

import com.google.gson.Gson;
import dev.epoch.bridge.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.*;
import net.minecraft.world.storage.FolderName;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;

/** Native load/save bridge. Enabled by default for ordinary single-player launches. */
public final class StreamingWorld {
    private static final String PROJECTION = "EpochTerrainProjection";
    private static final Gson JSON = new Gson();
    private static final Map<ServerWorld, Context> WORLDS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final int MAX_LOADED = 8192;

    static final class Projection {
        String worldId;
        long revision;
        WorldExtras.Carry residual;
    }
    /** Adopt legacy residuals both when decoding saved chunks and when generating new chunks. */
    static Projection initialProjection(String worldId, ChunkKey key, WorldExtras.Carry legacy) throws IOException {
        Projection projection = new Projection(); projection.worldId = worldId;
        if (legacy != null) projection.residual = legacy.chunk(key);
        return projection;
    }
    private static final class Resident {
        final Chunk chunk;
        final Projection projection;
        Resident(Chunk chunk, Projection projection) { this.chunk = chunk; this.projection = projection; }
    }
    private static final class LoadFailure extends RuntimeException {
        LoadFailure(Throwable cause) { super("Epoch refused unsafe terrain loading", cause); }
    }
    private static final class Context {
        final ServerWorld level;
        final Path world;
        final WorldBinding binding;
        WorldSession session;
        Path restart;
        net.minecraft.entity.player.ServerPlayerEntity player;
        final String instance;
        final ChunkStore store;
        final WorldExtras.Carry legacyResidual;
        StreamCheckpoint imported;
        final Map<ChunkKey, Resident> loaded = new LinkedHashMap<>();
        final Map<ChunkKey, ChunkSnapshot> detached = new LinkedHashMap<>();
        long detachedBytes;
        int peakDetached;
        final Map<Chunk, Projection> projections = new WeakHashMap<>();
        final ConcurrentMap<ChunkKey, ChunkStore.Versioned> incoming = new ConcurrentHashMap<>();
        final Set<ChunkKey> changed = ConcurrentHashMap.newKeySet();
        final ExecutorService reads = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Epoch authority reads"); thread.setDaemon(true); return thread;
        });
        volatile ChunkStore.Lease lease;
        ChunkWriteQueue writes;
        volatile Throwable failure;
        boolean applying;
        long ticks;
        final DestinationLoader destination;
        Exploration exploration;

        Context(ServerWorld level) throws Exception {
            this.level = level; destination = new DestinationLoader(level); world = level.getServer().getWorldPath(FolderName.ROOT).toRealPath();
            Path game = Minecraft.getInstance().gameDirectory.toPath();
            AutoRuntime.controlDirectory(game, Epoch.VERSION);
            instance = System.getProperty("epoch.instanceId");
            String shared = System.getProperty("epoch.authorityBinding", "");
            if (shared.isEmpty()) {
                if (AutoRuntime.managed()) throw new IOException("Managed streaming target is missing its authority binding");
                binding = WorldBinding.open(world, AutoRuntime.findProject(game).resolve(".minecraft/epoch/worlds"), Epoch.VERSION, instance);
            } else {
                binding = WorldBinding.read(Paths.get(shared));
                Path localBinding = world.resolve("epoch/authority.json");
                if (Files.exists(localBinding) && !WorldBinding.read(localBinding).worldId.equals(binding.worldId))
                    throw new IOException("Target save belongs to a different authority");
                binding.write(localBinding);
            }
            store = new ChunkStore(Paths.get(binding.authority), binding.worldId);
            legacyResidual = WorldExtras.readCarry(world.resolve("epoch/environment.json.gz"));
            boolean joining = AutoRuntime.managed() || !shared.isEmpty();
            session = WorldSession.open(Paths.get(binding.authority), instance, joining);
            try {
                if (!joining) {
                    Path saved = SavedWorld.latest(binding);
                    ChunkStore.Lease previous = store.lease();
                    if (saved != null) {
                        // No live member exists: authorize a new import from the last saved state.
                        restart = saved;
                    } else {
                        // Pre-checkpoint worlds retain their native player state and canonical terrain.
                        becomeWriter(store.transfer(previous.epoch, instance));
                    }
                }
            } catch (Exception error) { session.close(); throw error; }
        }

        void becomeWriter(ChunkStore.Lease next) {
            lease = next;
            writes = new ChunkWriteQueue(store, lease, MAX_LOADED, (key, revision) -> {
                Resident resident = loaded.get(key);
                if (resident != null) resident.projection.revision = revision;
                if (!writes.isPending(key)) {
                    ChunkSnapshot released = detached.remove(key);
                    if (released != null) detachedBytes -= retainedBytes(released);
                }
            });
        }

        ChunkSnapshot capture(ChunkKey key) throws Exception {
            ChunkSnapshot held = detached.get(key);
            if (held != null) return held;
            Resident resident = loaded.get(key);
            if (resident == null) throw new IOException("Dirty chunk was unloaded before persistence: " + key);
            return TerrainTransfer.capture(level, resident.chunk, resident.projection.residual);
        }

        void drainChanges() throws IOException {
            if (failure != null) throw new IOException("Streaming world stopped", failure);
            if (writes == null) return;
            writes.drain();
            for (ChunkKey key : new ArrayList<>(changed)) {
                Resident resident = loaded.get(key);
                if (resident != null) writes.mark(key, resident.projection.revision);
                changed.remove(key);
            }
        }

        void flush() throws Exception {
            drainChanges();
            if (writes != null) writes.flush(this::capture, 30000);
        }

        void flushKey(ChunkKey key) throws Exception {
            drainChanges();
            if (writes != null) writes.flushKey(key, this::capture, 30000);
        }

        void detach(ChunkKey key) throws Exception {
            drainChanges();
            if (detached.containsKey(key)) return;
            if (writes == null || !writes.isPending(key)) { loaded.remove(key); return; }
            ChunkSnapshot snapshot = capture(key);
            long bytes = retainedBytes(snapshot);
            // Backpressure only when the bounded detached cache cannot retain another snapshot.
            while (!detached.isEmpty() && (detached.size() >= 32 || detachedBytes + bytes > 32L * 1024 * 1024)) {
                // Amortize journal/publication costs using only existing detached snapshots.
                List<ChunkKey> oldest = new ArrayList<>(detached.keySet());
                writes.flushKeys(oldest.subList(0, Math.min(16, oldest.size())), this::capture, 30000);
            }
            if (bytes > 32L * 1024 * 1024) { flushKey(key); loaded.remove(key); return; }
            detached.put(key, snapshot); detachedBytes += bytes;
            writes.prioritize(key);
            peakDetached = Math.max(peakDetached, detached.size());
            loaded.remove(key);
            writes.tick(this::capture, 16, TimeUnit.MILLISECONDS.toNanos(3));
        }

        boolean sourceContains(ChunkKey key) throws IOException {
            return NativeRegion.hasFullChunk(Paths.get(binding.sourceWorld), key);
        }

    }

    // Conservative accounting for retained arrays, strings, collection entries and object overhead.
    static long retainedBytes(ChunkSnapshot snapshot) {
        long bytes = 1024L + snapshot.runs.length * 4L + snapshot.biomes.runs.length * 4L;
        for (String state : snapshot.palette) bytes += 128L + state.length() * 2L;
        for (String biome : snapshot.biomes.palette) bytes += 128L + biome.length() * 2L;
        for (Map.Entry<String,String> entry : snapshot.blockEntities.entrySet())
            bytes += 256L + (entry.getKey().length() + entry.getValue().length()) * 2L;
        return bytes;
    }

    static CompletableFuture<Void> travel(net.minecraft.entity.player.ServerPlayerEntity player, double x, double y, double z) throws Exception {
        Context context = context(player.getLevel());
        if (context == null || context.writes == null) throw new IOException("Streaming terrain is not active in this instance");
        return context.destination.start(player,x,y,z,player.yRot,player.xRot);
    }

    static void cancelTravel(net.minecraft.entity.player.ServerPlayerEntity player) {
        Context context = WORLDS.get(player.getLevel());
        if(context!=null) context.destination.close();
        player.displayClientMessage(new net.minecraft.util.text.StringTextComponent("已取消目的地加载"),true);
    }

    static boolean enabled(ServerWorld level) {
        return AutoRuntime.streamingEnabled() && level.dimension() == World.OVERWORLD && !level.getServer().isDedicatedServer();
    }

    private static Context context(ServerWorld level) {
        if (!enabled(level)) return null;
        synchronized (WORLDS) {
            Context context = WORLDS.get(level);
            if (context == null) try { context = new Context(level); WORLDS.put(level, context); }
            catch (Exception error) { throw new LoadFailure(error); }
            return context;
        }
    }

    /** Called only for scheduleChunkLoad, never for the native save path's blocking existence read. */
    public static CompletableFuture<Optional<CompoundNBT>> loading(CompletableFuture<Optional<CompoundNBT>> nativeRead,
            ServerWorld level, ChunkPos pos) {
        Context context = context(level); if (context == null) return nativeRead;
        ChunkKey key = new ChunkKey(pos.x, pos.z);
        try {
            if (!level.getServer().isSameThread()) throw new IOException("Native load scheduling left server thread");
            if (context.detached.containsKey(key)) context.flushKey(key);
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
        return nativeRead.thenApplyAsync(nativeTag -> {
            try {
                ChunkStore.Versioned canonical = context.store.read(key);
                if (canonical == null && !context.world.equals(Paths.get(context.binding.sourceWorld)) && context.sourceContains(key)) {
                    ChunkSnapshot source = SourceChunkRequests.fetch(Paths.get(context.binding.authority).resolve("requests"), context.binding.worldId, key, 30000);
                    canonical = context.store.read(key);
                    if (canonical == null) {
                        ChunkStore.Lease writer = context.lease;
                        if (writer == null) throw new IOException("Source did not commit the standby's requested terrain");
                        try { context.store.commit(writer, Collections.singletonList(new ChunkStore.Write(0, source))); }
                        catch (IOException race) { if (context.store.read(key) == null) throw race; }
                        canonical = context.store.read(key);
                    }
                }
                if (canonical == null) return nativeTag;
                if (context.incoming.size() >= MAX_LOADED) throw new IOException("Authority loading queue is full");
                if (context.incoming.putIfAbsent(key, canonical) != null) throw new IOException("Duplicate native chunk load: " + key);
                // An absent native file must not start a second version's terrain generator.
                CompoundNBT root = nativeTag.orElseGet(CompoundNBT::new);
                CompoundNBT tag = root.getCompound("Level"); root.put("Level", tag);
                tag.putString("Status", "minecraft:full"); tag.putInt("xPos", pos.x); tag.putInt("zPos", pos.z);
                tag.putBoolean("isLightOn", false);
                for (net.minecraft.nbt.INBT section : tag.getList("Sections", 10)) {
                    ((CompoundNBT)section).remove("BlockLight"); ((CompoundNBT)section).remove("SkyLight");
                }
                return Optional.of(root);
            } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
        }, context.reads);
    }

    public static CompoundNBT loadingSync(CompoundNBT nativeTag, ServerWorld level, ChunkPos pos) {
        try { return loading(CompletableFuture.completedFuture(Optional.ofNullable(nativeTag)), level, pos).get(30, TimeUnit.SECONDS).orElse(null); }
        catch (Exception error) { throw new LoadFailure(error); }
    }

    public static ChunkPrimer decoded(ChunkPrimer nativeChunk, ServerWorld level, ChunkPos pos, CompoundNBT tag) {
        Context context = context(level); if (context == null) return nativeChunk;
        try {
            Chunk chunk = nativeChunk instanceof ChunkPrimerWrapper ? ((ChunkPrimerWrapper)nativeChunk).getWrapped() : null;
            ChunkKey key = new ChunkKey(pos.x, pos.z);
            ChunkStore.Versioned canonical = context.incoming.remove(key);
            if (context.detached.containsKey(key)) {
                context.flushKey(key); canonical = context.store.read(key);
            }
            Projection projection = null;
            if (tag.contains(PROJECTION, 8)) {
                String text = tag.getString(PROJECTION);
                if (text.length() > 4 * 1024 * 1024) throw new IOException("Oversized chunk projection");
                projection = JSON.fromJson(text, Projection.class);
                if (projection == null || !context.binding.worldId.equals(projection.worldId) || projection.revision < 0)
                    throw new IOException("Native chunk belongs to a different world");
            }
            // A missing prefetch is also stale when another version committed meanwhile.
            ChunkStore.Versioned refreshed = context.store.read(key);
            if (refreshed == null && (canonical != null || (projection != null && projection.revision > 0)))
                throw new IOException("Authority lost a previously committed chunk");
            canonical = refreshed;
            if (canonical != null) {
                if (chunk == null) chunk = new Chunk(level, nativeChunk);
                TerrainTransfer.Prepared prepared = TerrainTransfer.prepare(level, canonical.chunk);
                context.applying = true;
                try { TerrainTransfer.apply(level, chunk, prepared); } finally { context.applying = false; }
                projection = new Projection(); projection.worldId = context.binding.worldId;
                projection.revision = canonical.revision; projection.residual = prepared.residual();
            }
            if (chunk != null) {
                if (projection == null) projection = initialProjection(context.binding.worldId,
                        new ChunkKey(pos.x, pos.z), context.legacyResidual);
                context.projections.put(chunk, projection);
                return new ChunkPrimerWrapper(chunk);
            }
            return nativeChunk;
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    public static CompoundNBT encoded(CompoundNBT tag, ServerWorld level, IChunk access) {
        Context context = WORLDS.get(level);
        if (context == null || !(access instanceof Chunk)) return tag;
        Chunk chunk = (Chunk)access;
        Projection projection = context.projections.get(chunk); if (projection == null) return tag;
        try {
            // Native and authority persistence cannot disagree about an acknowledged revision.
            context.drainChanges(); tag.putString(PROJECTION, JSON.toJson(projection)); return tag;
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    public static void dirty(IChunk access, boolean dirty) {
        if (!dirty || !(access instanceof Chunk)) return;
        Chunk chunk = (Chunk)access;
        if (!(chunk.getLevel() instanceof ServerWorld)) return;
        ServerWorld level = (ServerWorld)chunk.getLevel();
        Context context = WORLDS.get(level);
        if (context != null && !context.applying && context.writes != null) context.changed.add(new ChunkKey(chunk.getPos().x, chunk.getPos().z));
    }

    /** Vanilla otherwise turns arbitrary chunk-read failures into new empty/generatable chunks. */
    public static void rejectFallback(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof LoadFailure)
            throw new net.minecraft.crash.ReportedException(net.minecraft.crash.CrashReport.forThrowable(
                    new IllegalStateException("Authority chunk could not be restored; refusing regeneration", cause), "Epoch terrain load"));
    }

    static void loaded(ChunkEvent.Load event) {
        if (!(event.getWorld() instanceof ServerWorld) || !(event.getChunk() instanceof Chunk)) return;
        ServerWorld level = (ServerWorld)event.getWorld(); Chunk chunk = (Chunk)event.getChunk();
        Context context = context(level); if (context == null) return;
        try {
            if (context.loaded.size() >= MAX_LOADED) throw new IOException("Native resident chunk budget exhausted");
            ChunkKey key = new ChunkKey(chunk.getPos().x, chunk.getPos().z);
            Projection projection = context.projections.get(chunk);
            if (projection == null) {
                projection = initialProjection(context.binding.worldId, key, context.legacyResidual);
                context.projections.put(chunk, projection);
            }
            // Generation or a deferred FULL promotion may finish after the resume scan.
            // Restore the authority's terrain before this resident can become dirty.
            if (context.writes != null && context.writes.isPending(key)) context.flushKey(key);
            ChunkStore.Versioned latest = context.store.read(key);
            if (latest != null && latest.revision != projection.revision) {
                TerrainTransfer.Prepared prepared = TerrainTransfer.prepare(level, latest.chunk);
                context.applying = true;
                try { TerrainTransfer.apply(level, chunk, prepared); } finally { context.applying = false; }
                projection.revision = latest.revision; projection.residual = prepared.residual();
                level.getChunkSource().getLightEngine().lightChunk(chunk, false);
            }
            context.loaded.put(key, new Resident(chunk, projection));
            if (context.writes != null && projection.revision == 0) context.changed.add(key);
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    static void unloaded(ChunkEvent.Unload event) {
        if (!(event.getWorld() instanceof ServerWorld) || !(event.getChunk() instanceof Chunk)) return;
        ServerWorld level = (ServerWorld)event.getWorld(); Chunk chunk = (Chunk)event.getChunk();
        Context context = WORLDS.get(level); if (context == null) return;
        try {
            ChunkKey key = new ChunkKey(chunk.getPos().x, chunk.getPos().z);
            context.detach(key);
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    static void serviceRequests(ServerWorld level) throws Exception {
        Context context = WORLDS.get(level);
        if (context == null || !context.world.equals(Paths.get(context.binding.sourceWorld))) return;
        Path directory = Paths.get(context.binding.authority).resolve("requests");
        for (SourceChunkRequests.Request request : SourceChunkRequests.poll(directory, context.binding.worldId)) try {
            ChunkStore.Versioned existing = context.store.read(request.key());
            if (existing != null) { SourceChunkRequests.respond(directory, request, existing.chunk); continue; }
            if (!context.sourceContains(request.key()) && !context.loaded.containsKey(request.key()))
                throw new IOException("Requested source chunk has not been generated");
            Chunk chunk = level.getChunk(request.chunkX, request.chunkZ);
            Resident resident = context.loaded.get(request.key());
            ChunkSnapshot captured = TerrainTransfer.capture(level, chunk, resident == null ? null : resident.projection.residual);
            if (context.writes != null) {
                context.changed.add(request.key()); context.flushKey(request.key());
            } else {
                ChunkStore.Lease owner = context.store.lease();
                if (context.lease != null && owner.epoch == context.lease.epoch && owner.owner.equals(context.instance)) {
                    long revision = context.store.commit(context.lease, Collections.singletonList(new ChunkStore.Write(0, captured)));
                    if (resident != null) resident.projection.revision = revision;
                }
            }
            SourceChunkRequests.respond(directory, request, captured);
        } catch (Exception error) { SourceChunkRequests.reject(directory, request, error); }
    }

    static void tick(ServerWorld level) throws Exception {
        Context context = context(level); if (context == null) return;
        if (!context.level.getServer().getPlayerList().getPlayers().isEmpty()) context.player = (net.minecraft.entity.player.ServerPlayerEntity)context.level.getServer().getPlayerList().getPlayers().get(0);
        context.destination.tick();
        if (context.exploration != null) context.exploration.tick(context);
        ClientHooks.bridge().diagnostics.put("destination", context.destination.diagnostics());
        context.drainChanges();
        if (context.writes != null) context.writes.tick(context::capture, context.detached.isEmpty() ? 2 : 16, TimeUnit.MILLISECONDS.toNanos(3));
        ClientHooks.bridge().diagnostics.put("streamingProtocol", 3);
        ClientHooks.bridge().diagnostics.put("streamingWorldId", context.binding.worldId);
        ClientHooks.bridge().diagnostics.put("streamingBinding", context.world.resolve("epoch/authority.json").toString());
        ClientHooks.bridge().diagnostics.put("streamingLoaded", context.loaded.size());
        ClientHooks.bridge().diagnostics.put("streamingDirty", context.writes == null ? 0 : context.writes.pendingCount());
        ClientHooks.bridge().diagnostics.put("streamingDetached", context.detached.size());
        ClientHooks.bridge().diagnostics.put("streamingDetachedBytes", context.detachedBytes);
        ClientHooks.bridge().diagnostics.put("streamingTicks", ++context.ticks);
    }

    static void configureSnapshot(ServerWorld level, Snapshot snapshot, double x, double z) {
        if (!enabled(level)) return;
        ChunkKey center = ChunkKey.at(x, z);
        snapshot.x0 = Math.max(-30000000, Math.min(30000000 - snapshot.sizeX, center.x * 16 - 32));
        snapshot.z0 = Math.max(-30000000, Math.min(30000000 - snapshot.sizeZ, center.z * 16 - 32));
    }

    static String[] restoreBiomes(ServerWorld level, Snapshot bounds, String[] nativeCells, WorldExtras.Carry legacy) throws Exception {
        Context context = context(level);
        if (context == null) return legacy == null ? nativeCells : legacy.restore(bounds, nativeCells);
        String[] restored = nativeCells.clone();
        for (int cz = bounds.minChunkZ(); cz <= bounds.maxChunkZ(); cz++) for (int cx = bounds.minChunkX(); cx <= bounds.maxChunkX(); cx++) {
            Projection projection = context.projections.get(level.getChunk(cx, cz));
            if (projection == null || projection.residual == null) continue;
            String[] original = projection.residual.original.expand(16384), applied = projection.residual.applied.expand(16384);
            for (int y = 0; y < 64; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int local = (y * 16 + z) * 16 + x;
                int index = (y * bounds.sizeZ + cz * 16 + z - bounds.z0) * bounds.sizeX + cx * 16 + x - bounds.x0;
                if (nativeCells[index].equals(applied[local])) restored[index] = original[local];
            }
        }
        return restored;
    }

    static Path restart(ServerWorld level) {
        Context context = context(level); return context == null ? null : context.restart;
    }

    static void restarted(ServerWorld level) {
        Context context = context(level); if (context != null) context.restart = null;
    }

    static void playerSaved(net.minecraftforge.event.entity.player.PlayerEvent.SaveToFile event) {
        if (!(event.getPlayer() instanceof net.minecraft.entity.player.ServerPlayerEntity)) return;
        net.minecraft.entity.player.ServerPlayerEntity player = (net.minecraft.entity.player.ServerPlayerEntity)event.getPlayer();
        Context context = WORLDS.get(player.getLevel());
        if (context == null) return;
        context.player = player;
        try { saveRestart(context); }
        catch (Exception error) {
            ClientHooks.bridge().diagnostics.put("savedWorldError", error.toString());
            error.printStackTrace();
        }
    }

    private static void saveRestart(Context context) throws Exception {
        if (context.writes == null || context.failure != null || context.restart != null || context.player == null) return;
        ChunkStore.Lease owner = context.store.lease();
        if (context.lease.epoch != owner.epoch || !context.instance.equals(owner.owner)) return;
        context.flush();
        Snapshot snapshot = Handoff.instance.exportWorld(context.level.getServer(), context.player);
        // Capturing the player's neighborhood may load additional native chunks.
        context.flush();
        SavedWorld.publish(snapshot, context.binding, context.lease);
        AutoRuntime.prepareSavedWorlds(context.world.resolve("epoch/authority.json"));
    }

    static void exported(ServerWorld level, Path snapshot) throws Exception {
        Context context = context(level);
        if (context == null) { Files.deleteIfExists(StreamCheckpoint.path(snapshot)); return; }
        if (context.writes == null) throw new IOException("Frozen standby cannot export as the authoritative writer");
        context.destination.close();
        context.flush(); context.writes.close(); context.writes = null;
        StreamCheckpoint.publish(snapshot, context.binding, context.lease);
    }

    static void verifyImport(ServerWorld level, Path snapshot) throws Exception {
        Context context = context(level);
        if (context == null) {
            if (Files.exists(StreamCheckpoint.path(snapshot))) throw new IOException("This instance has not enabled the streaming protocol");
            return;
        }
        if (context.writes != null) throw new IOException("Cannot overwrite a running authority writer");
        context.imported = snapshot.equals(context.restart) ? SavedWorld.authorize(snapshot, context.binding, context.store)
                : StreamCheckpoint.verify(snapshot, context.binding, context.store);
        context.destination.close();
    }

    static void resume(ServerWorld level) throws Exception {
        Context context = context(level); if (context == null) return;
        if (context.failure != null) throw new IOException("Streaming context requires recovery", context.failure);
        ChunkStore.Lease owner = context.store.lease();
        if (context.writes != null) {
            if (owner.epoch != context.lease.epoch || !owner.owner.equals(context.instance)) throw new IOException("Writer lease was revoked");
            return;
        }
        long expected = context.imported != null ? context.imported.sourceEpoch : context.lease == null ? -1 : context.lease.epoch;
        String previous = context.imported != null ? context.imported.sourceInstance : context.instance;
        if (owner.epoch != expected || !owner.owner.equals(previous)) throw new IOException("Handoff ownership changed; refusing a second writer");
        List<CompletableFuture<?>> lighting = new ArrayList<>();
        for (Resident resident : new ArrayList<>(context.loaded.values())) {
            ChunkKey key = new ChunkKey(resident.chunk.getPos().x, resident.chunk.getPos().z);
            ChunkStore.Versioned latest = context.store.read(key);
            if (latest == null || latest.revision == resident.projection.revision) continue;
            TerrainTransfer.Prepared prepared = TerrainTransfer.prepare(level, latest.chunk);
            context.applying = true;
            try { TerrainTransfer.apply(level, resident.chunk, prepared); } finally { context.applying = false; }
            resident.projection.revision = latest.revision; resident.projection.residual = prepared.residual();
            lighting.add(level.getChunkSource().getLightEngine().lightChunk(resident.chunk, true));
        }
        CompletableFuture<?> lights = CompletableFuture.allOf(lighting.toArray(new CompletableFuture<?>[0]));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!lights.isDone()) {
            level.getChunkSource().getLightEngine().tryScheduleUpdate(); level.getChunkSource().pollTask();
            if (System.nanoTime() >= deadline) throw new IOException("Standby terrain lighting timed out");
            Thread.sleep(1);
        }
        lights.join();
        // Lease publication is the handoff commit point. Everything above remains recoverable by the source.
        context.becomeWriter(context.store.transfer(expected, context.instance)); context.imported = null;
        for (Map.Entry<ChunkKey, Resident> entry : context.loaded.entrySet())
            if (entry.getValue().projection.revision == 0) context.changed.add(entry.getKey());
    }

    static void flush(ServerWorld level) throws Exception {
        Context context = WORLDS.get(level); if (context != null) context.flush();
    }

    /** Explicit lab-only native integration checks, exercised through the actual transformed client. */
    static Map<String, Object> selfTest(ServerWorld level) throws Exception {
        if (!Boolean.getBoolean("epoch.testHarness")) throw new IOException("Streaming self-test requires an isolated test harness");
        Context context = context(level); if (context == null) throw new IOException("Streaming is disabled");
        ChunkPos pos = new ChunkPos(-100, 300); ChunkKey key = new ChunkKey(pos.x, pos.z);
        Chunk chunk = level.getChunk(pos.x, pos.z);
        net.minecraft.util.math.BlockPos marker = new net.minecraft.util.math.BlockPos(pos.getMinBlockX() + 3, 64, pos.getMinBlockZ() + 5);
        level.setBlock(marker, net.minecraft.block.Blocks.CHEST.defaultBlockState(), 2);
        net.minecraft.tileentity.ChestTileEntity chest = (net.minecraft.tileentity.ChestTileEntity)level.getBlockEntity(marker);
        chest.setItem(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 7)); chest.setChanged();
        net.minecraft.block.BlockState special = NativeStates.context().decode("{Name:\"minecraft:cherry_planks\"}",
                new BlockStateCompatibility.Report(Epoch.VERSION, Epoch.VERSION), 0);
        if (special != null) level.setBlock(marker.east(), special, 2);
        // Seed stored cave air directly: World.setBlock can skip air-to-air changes.
        if (chunk.getSections()[90 >> 4] == null) chunk.getSections()[90 >> 4] = new ChunkSection(80);
        chunk.getSections()[90 >> 4].setBlockState(marker.getX() & 15, 90 & 15, marker.getZ() & 15,
                net.minecraft.block.Blocks.CAVE_AIR.defaultBlockState());
        chunk.setUnsaved(true);
        context.flush();
        ChunkStore.Versioned expected = context.store.read(key);
        if (expected == null || expected.chunk.blockEntities.isEmpty()) throw new IOException("Native dirtiness was not captured");
        CompoundNBT stale = net.minecraft.world.chunk.storage.ChunkSerializer.write(level, chunk);
        // Authority advances, while the simulated destination still holds the previous native bytes.
        level.setBlock(marker, net.minecraft.block.Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        Resident originalResident = context.loaded.get(key);
        context.detach(key);
        if (!context.detached.containsKey(key) || context.loaded.containsKey(key))
            throw new IOException("Pending unload did not retain its snapshot independently");
        CompoundNBT merged = loading(CompletableFuture.completedFuture(Optional.of(stale)), level, pos).get(30, TimeUnit.SECONDS).get();
        ChunkStore.Versioned latest = context.store.read(key);
        if (latest.revision <= expected.revision || context.detached.containsKey(key) || context.writes.isPending(key))
            throw new IOException("Immediate reload did not commit the latest detached generation");
        // This adapter probe detached the registry entry, not the provider's native chunk.
        originalResident.projection.revision = latest.revision; context.loaded.put(key, originalResident);
        ChunkPrimer restored = net.minecraft.world.chunk.storage.ChunkSerializer.read(level, level.getStructureManager(), level.getPoiManager(), pos, merged);
        Chunk restoredFull = ((ChunkPrimerWrapper)restored).getWrapped();
        if (!restoredFull.getBlockState(marker).is(net.minecraft.block.Blocks.GOLD_BLOCK))
            throw new IOException("Stale native content won over canonical terrain; load transformer missing");
        if (!restoredFull.getSections()[90 >> 4].getBlockState(marker.getX() & 15, 90 & 15, marker.getZ() & 15).is(net.minecraft.block.Blocks.CAVE_AIR))
            throw new IOException("Air-section capture erased distinct cave air");
        if (restoredFull.getBlockEntity(marker) != null) throw new IOException("Deleted container was resurrected from packed NBT");
        CompoundNBT absent = loading(CompletableFuture.completedFuture(Optional.empty()), level, pos).get(30, TimeUnit.SECONDS).get();
        Chunk withoutLocal = ((ChunkPrimerWrapper)net.minecraft.world.chunk.storage.ChunkSerializer.read(level, level.getStructureManager(), level.getPoiManager(), pos, absent)).getWrapped();
        if (!withoutLocal.getBlockState(marker).is(net.minecraft.block.Blocks.GOLD_BLOCK))
            throw new IOException("Missing native file did not restore canonical terrain");
        if (!stale.contains(PROJECTION, 8)) throw new IOException("Projection was not embedded by the native save transformer");
        // Simulate a standby prefetch miss followed by another version's commit.
        context.incoming.remove(key);
        Chunk lateDecode = ((ChunkPrimerWrapper)net.minecraft.world.chunk.storage.ChunkSerializer.read(
                level, level.getStructureManager(), level.getPoiManager(), pos, stale.copy())).getWrapped();
        if (!lateDecode.getBlockState(marker).is(net.minecraft.block.Blocks.GOLD_BLOCK))
            throw new IOException("Missing prefetch bypassed current authority");
        // Simulate an already decoded chunk promoted to FULL only after resume.
        context.projections.put(lateDecode, initialProjection(context.binding.worldId, key, null));
        lateDecode.getSections()[64 >> 4].setBlockState(marker.getX() & 15, 0, marker.getZ() & 15,
                net.minecraft.block.Blocks.STONE.defaultBlockState());
        loaded(new ChunkEvent.Load(lateDecode));
        if (context.loaded.get(key).projection.revision != latest.revision
                || !lateDecode.getBlockState(marker).is(net.minecraft.block.Blocks.GOLD_BLOCK))
            throw new IOException("Late FULL registration retained stale terrain or revision");
        context.loaded.put(key, originalResident);
        net.minecraft.entity.player.ServerPlayerEntity player = level.getServer().getPlayerList().getPlayers().get(0);
        destinationChecks(context, player);
        player.teleportTo(level, marker.getX() + .5, 66, marker.getZ() + .5, 0, 0);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("worldId", context.binding.worldId); report.put("chunk", key.toString());
        report.put("nativeDirtyTracking", true); report.put("staleNativeReplaced", true); report.put("pendingReloadRestored", true); report.put("destinationCancellationChecked", true);
        report.put("deletedContainerAbsent", true);
        report.put("missingPrefetchReconciled", true); report.put("lateFullRegistrationReconciled", true);
        report.put("absentNativeRestored", true); report.put("projectionEmbedded", true);
        report.put("revision", latest.revision); report.put("playerX", player.getX()); report.put("playerZ", player.getZ());
        return report;
    }

    private static void destinationChecks(Context context, net.minecraft.entity.player.ServerPlayerEntity player) throws Exception {
        double x=player.getX(),y=player.getY(),z=player.getZ();
        int accepted=context.level.getServer().getCommands().performCommand(
                player.createCommandSourceStack().withPermission(2),"epoch travel 128 80 128");
        if(accepted!=1) throw new IOException("Travel command did not create a request");
        CompletableFuture<Void> completion=context.destination.currentCompletion();
        boolean duplicateRejected=false;
        try { context.destination.start(player,0,80,0,0,0); } catch(IOException expected) { duplicateRejected=true; }
        if(!duplicateRejected) throw new IOException("Destination queue accepted duplicate requests");
        context.destination.tick();
        if(((Number)context.destination.diagnostics().get("heldTickets")).intValue()==0)
            throw new IOException("Cancellation probe did not exercise ticket cleanup");
        int cancelled=context.level.getServer().getCommands().performCommand(
                player.createCommandSourceStack().withPermission(2),"epoch travel cancel");
        Map<String,Object> report=context.destination.diagnostics();
        if(cancelled!=1 || !completion.isCompletedExceptionally() || !"cancelled".equals(report.get("status"))
                || ((Number)report.get("heldTickets")).intValue()!=0 || player.getX()!=x || player.getY()!=y || player.getZ()!=z)
            throw new IOException("Cancelling destination leaked tickets or moved the player");
        boolean invalidRejected=false;
        try { context.destination.start(player,0,-1,0,0,0); } catch(IOException expected) { invalidRejected=true; }
        if(!invalidRejected) throw new IOException("Destination accepted unsupported height");
    }

    private static final class Exploration {
        final net.minecraft.entity.player.ServerPlayerEntity player;
        final net.minecraft.util.math.BlockPos marker = new net.minecraft.util.math.BlockPos(-1597, 64, 4805);
        final ChunkKey origin = new ChunkKey(-100, 300);
        final List<Double> intervals = new ArrayList<>();
        final Map<String,List<Double>> phaseIntervals = new LinkedHashMap<>();
        final Map<String,Map<String,Object>> phaseStarts = new LinkedHashMap<>();
        final long started = System.nanoTime();
        final boolean wasFlying, couldFly;
        long previous = started, peakHeap;
        int step, peakLoaded, peakDirty, missingAtPlayer;
        boolean unloaded;
        CompletableFuture<Void> destination;
        int returnedAt = -1;
        Exploration(net.minecraft.entity.player.ServerPlayerEntity player) {
            this.player = player; wasFlying = player.abilities.flying; couldFly = player.abilities.mayfly;
            player.abilities.mayfly = true; player.abilities.flying = true; player.onUpdateAbilities();
        }
        void tick(Context context) throws Exception {
            long now = System.nanoTime(); double interval = (now - previous) / 1e6;
            intervals.add(interval); previous = now;
            String phase = step == 0 ? "start" : step <= 256 ? "outbound" : step <= 656 ? "waitForUnload" : returnedAt < 0 ? "prepareDestination" : "return";
            phaseIntervals.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(interval);
            if (!phaseStarts.containsKey(phase)) {
                Map<String,Object> start = new LinkedHashMap<>();
                start.put("observedAtEpochMs", System.currentTimeMillis()); start.put("loaded", context.loaded.size());
                start.put("dirty", context.writes == null ? 0 : context.writes.pendingCount()); start.put("detached", context.detached.size());
                phaseStarts.put(phase, start);
            }
            peakLoaded = Math.max(peakLoaded, context.loaded.size());
            peakDirty = Math.max(peakDirty, context.writes == null ? 0 : context.writes.pendingCount());
            Runtime runtime = Runtime.getRuntime(); peakHeap = Math.max(peakHeap, runtime.totalMemory() - runtime.freeMemory());
            step++;
            if (step <= 256) player.connection.teleport(-1596.5 + step * 2, 80, 4805.5, 0, 0);
            if (step <= 656) {
                if (!context.loaded.containsKey(ChunkKey.at(player.getX(), player.getZ()))) missingAtPlayer++;
                if (step > 256 && !context.loaded.containsKey(origin)) unloaded = true;
            }
            if (step == 657) {
                if (!unloaded) throw new IOException("Exploration did not unload its origin chunk");
                ChunkStore.Versioned saved = context.store.read(origin);
                if (saved == null) throw new IOException("Exploration lost authority origin");
                int accepted = context.level.getServer().getCommands().performCommand(
                        player.createCommandSourceStack().withPermission(2), "epoch travel -1596.5 66 4805.5");
                if(accepted!=1) throw new IOException("Travel command was rejected");
                destination = context.destination.currentCompletion();
            }
            if (destination != null && destination.isDone() && returnedAt < 0) {
                destination.join(); returnedAt=step;
                player.abilities.flying=false; player.onUpdateAbilities();
            }
            if (returnedAt >= 0 && step == returnedAt + 60) {
                Map<String,Object> report = positionTest(context.level);
                if (!context.level.getBlockState(marker).is(net.minecraft.block.Blocks.EMERALD_BLOCK))
                    throw new IOException("Exploration return lost origin edit");
                Collections.sort(intervals);
                report.put("distanceBlocks", 512); report.put("originUnloaded", unloaded); report.put("editRestored", true);
                report.put("scriptedMovement", "2 blocks per server tick, then prepared return teleport");
                report.put("ticksWithoutResidentAtPlayer", missingAtPlayer);
                report.put("tickIntervalP95Ms", intervals.get((int)Math.ceil(intervals.size() * .95) - 1));
                report.put("tickIntervalP99Ms", intervals.get((int)Math.ceil(intervals.size() * .99) - 1));
                report.put("tickIntervalMaxMs", intervals.get(intervals.size() - 1));
                report.put("elapsedMs", (now - started) / 1e6); report.put("peakLoaded", peakLoaded);
                report.put("peakDetached", context.peakDetached); report.put("peakDirty", peakDirty); report.put("peakHeapBytes", peakHeap);
                Map<String,Object> phases = new LinkedHashMap<>();
                for (Map.Entry<String,List<Double>> entry : phaseIntervals.entrySet()) {
                    List<Double> values = entry.getValue(); Collections.sort(values);
                    Map<String,Object> stats = new LinkedHashMap<>(phaseStarts.get(entry.getKey()));
                    stats.put("samples", values.size()); stats.put("totalMs", values.stream().mapToDouble(Double::doubleValue).sum());
                    stats.put("p95Ms", values.get((int)Math.ceil(values.size() * .95) - 1));
                    stats.put("p99Ms", values.get((int)Math.ceil(values.size() * .99) - 1));
                    stats.put("maxMs", values.get(values.size() - 1)); phases.put(entry.getKey(), stats);
                }
                report.put("phases", phases);
                report.put("destination", context.destination.diagnostics());
                report.put("complete", true);
                player.abilities.flying = wasFlying; player.abilities.mayfly = couldFly; player.onUpdateAbilities();
                context.exploration = null; ClientHooks.bridge().diagnostics.put("streamingExploration", report);
            }
        }
    }

    static void exploreTest(ServerWorld level) throws Exception {
        if (!Boolean.getBoolean("epoch.testHarness")) throw new IOException("Requires isolated test harness");
        Context context = context(level);
        if (context == null || context.exploration != null) throw new IOException("Exploration unavailable");
        net.minecraft.entity.player.ServerPlayerEntity player = level.getServer().getPlayerList().getPlayers().get(0);
        level.setBlock(new net.minecraft.util.math.BlockPos(-1597,64,4805), net.minecraft.block.Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
        context.exploration = new Exploration(player);
        ClientHooks.bridge().diagnostics.put("streamingExploration", Collections.singletonMap("complete", false));
    }

    static Map<String, Object> positionTest(ServerWorld level) throws Exception {
        if (!Boolean.getBoolean("epoch.testHarness")) throw new IOException("Requires test harness");
        net.minecraft.entity.player.ServerPlayerEntity player = level.getServer().getPlayerList().getPlayers().get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("x", player.getX()); result.put("y", player.getY()); result.put("z", player.getZ());
        result.put("underfoot", level.getBlockState(player.blockPosition().below()).toString());
        if (player.getY() < 64 || player.getY() > 67) throw new IOException("Remote player did not land safely: " + result);
        return result;
    }

    static Map<String, Object> inspectTest(ServerWorld level, boolean edit) throws Exception {
        if (!Boolean.getBoolean("epoch.testHarness")) throw new IOException("Requires test harness");
        net.minecraft.util.math.BlockPos marker = new net.minecraft.util.math.BlockPos(-1597, 64, 4805);
        net.minecraft.util.math.BlockPos origin = new net.minecraft.util.math.BlockPos(0, 64, 0);
        if (edit) {
            level.setBlock(marker, net.minecraft.block.Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
            level.setBlock(origin, net.minecraft.block.Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
            flush(level);
        }
        Map<String, Object> report = positionTest(level);
        report.put("marker", level.getBlockState(marker).toString());
        report.put("origin", level.getBlockState(origin).toString());
        report.put("special", level.getBlockState(marker.east()).toString());
        net.minecraft.tileentity.TileEntity special = level.getBlockEntity(marker.east());
        if (special instanceof Epoch.UnknownTile) report.put("originalSpecial", ((Epoch.UnknownTile)special).originalState);
        return report;
    }

    static Map<String, Object> restoreTest(ServerWorld level) throws Exception {
        if (!Boolean.getBoolean("epoch.testHarness")) throw new IOException("Requires test harness");
        Context context = context(level); if (context == null) throw new IOException("Streaming disabled");
        Chunk chunk = level.getChunk(-100, 300);
        net.minecraft.util.math.BlockPos marker = new net.minecraft.util.math.BlockPos(-1597, 64, 4805);
        if (!chunk.getBlockState(marker).is(net.minecraft.block.Blocks.GOLD_BLOCK)
                || chunk.getBlockEntity(marker) != null) throw new IOException("Restart lost the latest block/container deletion");
        Map<String, Object> result = positionTest(level);
        result.put("worldId", context.binding.worldId); result.put("restartRestored", true);
        return result;
    }

    static void stopping(FMLServerStoppingEvent event) {
        synchronized (WORLDS) {
            for (Map.Entry<ServerWorld, Context> entry : WORLDS.entrySet()) {
                if (entry.getKey().getServer() != event.getServer()) continue;
                Context context = entry.getValue();
                try {
                    if (context.player != null && context.player.isAlive()) saveRestart(context);
                context.destination.close();
                    context.flush();
                    if (context.writes != null) { context.writes.close(); context.writes = null; }
                } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
            }
        }
    }

    static void stopped(FMLServerStoppedEvent event) {
        synchronized (WORLDS) {
            for (Iterator<Map.Entry<ServerWorld, Context>> it = WORLDS.entrySet().iterator(); it.hasNext();) {
                Map.Entry<ServerWorld, Context> entry = it.next();
                if (entry.getKey().getServer() != event.getServer()) continue;
                entry.getValue().reads.shutdown();
                try { entry.getValue().session.close(); } catch (IOException error) { throw new LoadFailure(error); }
                it.remove();
            }
        }
    }
}
