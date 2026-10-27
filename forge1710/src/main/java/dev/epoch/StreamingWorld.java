package dev.epoch;

import com.google.gson.Gson;
import dev.epoch.bridge.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.chunk.*;

import net.minecraftforge.event.world.ChunkEvent;


/** Native load/save bridge. Enabled by default for ordinary single-player launches. */
public final class StreamingWorld {
    private static final String PROJECTION = "EpochTerrainProjection";
    private static final Gson JSON = new Gson();
    private static final Map<WorldServer, Context> WORLDS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final int MAX_LOADED = 8192;

    static final class Projection {
        String worldId;
        long revision;
        String format, cacheStamp;
        transient boolean baselineEligible;
        WorldExtras.Carry residual;
    }
    /** Adopt legacy residuals both when decoding saved chunks and when generating new chunks. */
    static Projection initialProjection(String worldId, ChunkKey key, WorldExtras.Carry legacy) throws IOException {
        Projection projection = new Projection(); projection.worldId = worldId;
        projection.format = ProjectionCache.stamp(Epoch.class);
        if (legacy != null) projection.residual = legacy.chunk(key);
        return projection;
    }
    private static Projection savedProjection(Context context, Projection live, ChunkKey key) throws IOException {
        Projection saved = new Projection(); saved.worldId = live.worldId; saved.revision = live.revision;
        saved.residual = live.residual; saved.format = live.format;
        saved.cacheStamp = live.cacheStamp;
        if (context.changed.contains(key) || (context.writes != null && context.writes.isPending(key))) saved.cacheStamp = null;
        else if (context.writes != null) saved.cacheStamp = live.format;
        return saved;
    }

    private static boolean reusable(Context context, Projection projection, long revision) throws IOException {
        return projection != null && ProjectionCache.reusable(projection.worldId, context.binding.worldId,
                projection.revision, revision, projection.cacheStamp, ProjectionCache.stamp(Epoch.class));
    }

    private static final class Resident {
        final Chunk chunk;
        final Projection projection;
        Resident(Chunk chunk, Projection projection) { this.chunk = chunk; this.projection = projection; }
    }
    private static final class LoadFailure extends Error {
        LoadFailure(Throwable cause) { super("Epoch refused unsafe terrain loading", cause); }
    }
    private static final class Context {
        final WorldServer level;
        final Path world;
        final WorldBinding binding;
        WorldSession session;
        Path restart;
        net.minecraft.entity.player.EntityPlayerMP player;
        final String instance;
        final ChunkStore store;
        final WorldExtras.Carry legacyResidual;
        StreamCheckpoint imported;
        final Map<ChunkKey, Resident> loaded = new LinkedHashMap<>();
        final Map<ChunkKey, ChunkSnapshot> detached = new LinkedHashMap<>();
        long detachedBytes;
        int peakDetached;
        final Map<Chunk, Projection> projections = new WeakHashMap<>();
        final IncomingChunks incoming = new IncomingChunks();
        final Set<ChunkKey> changed = ConcurrentHashMap.newKeySet();
        final AuthorityReads reads = new AuthorityReads();
        final SavedWorld.Publisher saves = new SavedWorld.Publisher();
        volatile ChunkStore.Lease lease;
        ChunkWriteQueue writes;
        volatile Throwable failure;
        boolean applying;
        long ticks, nativeCacheHits, terrainConversions;
        final DestinationLoader destination;

        Context(WorldServer level) throws Exception {
            try {
            StreamingMode.requireLegacy(Epoch.class.getClassLoader());
            this.level = level; destination = new DestinationLoader(level); world = level.getSaveHandler().getWorldDirectory().toPath().toRealPath();
            Path game = Minecraft.getMinecraft().mcDataDir.toPath();
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
            if (world.equals(Paths.get(binding.sourceWorld)) && !Epoch.VERSION.equals(binding.sourceVersion))
                throw new IOException("Open the original native world with its original Minecraft version");
            store = saves.openStore(Paths.get(binding.authority), binding.worldId);
            legacyResidual = saves.readCarry(world.resolve("epoch/environment.json.gz"));
            boolean joining = AutoRuntime.managed() || !shared.isEmpty();
            session = WorldSession.open(Paths.get(binding.authority), instance, joining);
            try {
                WorldSession.Startup startup = session.initialize(binding, store, saves, instance, joining);
                restart = startup.restart;
                if (startup.writer != null) becomeWriter(startup.writer);
            } catch (Exception error) { session.close(); throw error; }
            AutoRuntime.bindWorld(world.resolve("epoch/authority.json"), binding.worldId);
            } catch (Exception error) {
                reads.close();
                try { saves.close(); } catch (IOException closing) { error.addSuppressed(closing); }
                if (session != null) try { session.close(); } catch (IOException closing) { error.addSuppressed(closing); }
                throw error;
            }
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

        void sealNative(Chunk chunk, Projection projection) throws Exception {
            if (projection.revision != 0) return;
            ChunkKey key = new ChunkKey(chunk.xPosition, chunk.zPosition);
            if (world.equals(Paths.get(binding.sourceWorld)) && projection.baselineEligible && store.baselineCovered(key) && !store.baselineRecorded(key)) {
                projection.revision = saves.acceptBaseline(store, TerrainTransfer.capture(level, chunk, projection.residual));
            } else if (!store.baselineRecorded(key)) store.markGenerated(key);
            projection.baselineEligible = false;
        }

        boolean sourceContains(ChunkKey key) throws IOException {
            return saves.sourceContains(binding, key);
        }

    }

    // Conservative accounting for retained arrays, strings, collection entries and object overhead.
    static long retainedBytes(ChunkSnapshot snapshot) { return snapshot.retainedBytes(); }

    static CompletableFuture<Void> travel(net.minecraft.entity.player.EntityPlayerMP player,double x,double y,double z) throws Exception {
        Context context=context(player.getServerForPlayer());
        if(context==null||context.writes==null)throw new IOException("Streaming terrain is not active");
        return context.destination.start(player,x,y,z);
    }
    static void cancelTravel(net.minecraft.entity.player.EntityPlayerMP player) {
        Context context=WORLDS.get(player.getServerForPlayer());if(context!=null)context.destination.close();
    }

    @cpw.mods.fml.common.eventhandler.SubscribeEvent
    public void worldOpened(net.minecraftforge.event.world.WorldEvent.Load event) {
        if (!(event.world instanceof WorldServer)) return;
        WorldServer level = (WorldServer)event.world;
        if (level.provider.dimensionId != 0 || net.minecraft.server.MinecraftServer.getServer().isDedicatedServer()) return;
        StreamingMode.requireBoundWorld(level.getSaveHandler().getWorldDirectory().toPath());
        context(level);
    }

    static boolean enabled(WorldServer level) {
        return AutoRuntime.streamingEnabled() && level.provider.dimensionId == 0 && !cpw.mods.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().isDedicatedServer();
    }

    private static Context context(WorldServer level) {
        if (!enabled(level)) return null;
        synchronized (WORLDS) {
            Context context = WORLDS.get(level);
            if (context == null) try { context = new Context(level); WORLDS.put(level, context); }
            catch (Exception error) { throw new LoadFailure(error); }
            return context;
        }
    }

    private static ChunkStore.Versioned canonical(Context context, ChunkKey key) throws Exception {
        if (!cpw.mods.fml.common.FMLCommonHandler.instance().getEffectiveSide().isServer())
            throw new IOException("Authority lookup requires server side");
        if (context.detached.containsKey(key)) context.flushKey(key);
        ChunkStore.Versioned value = context.reads.read(context.store, key);
        if (value == null && !context.world.equals(Paths.get(context.binding.sourceWorld)) && context.sourceContains(key)) {
            ChunkSnapshot source = SourceChunkRequests.fetch(Paths.get(context.binding.authority).resolve("requests"), context.binding.worldId, key, 30000);
            value = context.reads.read(context.store, key);
            if (value == null) {
                if (context.lease == null) throw new IOException("Source did not commit requested terrain");
                context.saves.commit(context.store, context.lease, Collections.singletonList(new ChunkStore.Write(0, source)));
                value = context.reads.read(context.store, key);
            }
        }
        return value;
    }

    /** Main-thread entry before vanilla chooses between native loading and generation. */
    public static Chunk load(net.minecraft.world.gen.ChunkProviderServer provider, int x, int z, Runnable callback) {
        if (!enabled(provider.worldObj) || provider.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x, z)) != null) return null;
        Context context = context(provider.worldObj);
        try {
            if (!cpw.mods.fml.common.FMLCommonHandler.instance().getEffectiveSide().isServer()) throw new IOException("Authority load must run on server thread");
            ChunkKey key = new ChunkKey(x, z);
            if (context.detached.containsKey(key)) context.flushKey(key);
            long revision = context.store.revision(key);
            boolean regenerated = context.store.discarded(key);
            ChunkStore.Versioned value = revision == 0 && !regenerated ? canonical(context, key) : null;
            if (revision == 0 && value == null && !regenerated) return null;
            // Existing native entities are loaded by vanilla; beforeEntities replaces terrain first.
            Chunk chunk = regenerated ? provider.currentChunkProvider.provideChunk(x, z) : provider.currentChunkLoader.loadChunk(provider.worldObj, x, z);
            if (chunk == null) {
                if (value == null) value = canonical(context, key);
                chunk = new Chunk(provider.worldObj, x, z);
                applyCanonical(context, chunk, value);
            }
            if (!regenerated) chunk.isTerrainPopulated=true;
            provider.loadedChunkHashMap.add(ChunkCoordIntPair.chunkXZ2Int(x, z), chunk);provider.loadedChunks.add(chunk);
            // Re-enter the already-loaded vanilla branch to cancel any queued unload.
            provider.loadChunk(x, z);
            chunk.onChunkLoad();
            // The imported chunk is populated, but it can be the final neighbour that
            // allows native chunks to populate. Skipping this leaves them unsendable.
            chunk.populateChunk(provider, provider, x, z);
            chunk.func_150809_p();
            if (callback != null) callback.run();
            return chunk;
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    private static void applyCanonical(Context context, Chunk chunk, ChunkStore.Versioned value) throws Exception {
        TerrainTransfer.Prepared prepared = TerrainTransfer.prepare(context.level, value.chunk);
        context.applying = true;
        try { TerrainTransfer.apply(context.level, chunk, prepared); } finally { context.applying = false; }
        Projection projection = new Projection(); projection.worldId = context.binding.worldId;
        projection.revision = value.revision; projection.residual = prepared.residual();
        projection.format = ProjectionCache.stamp(Epoch.class); projection.cacheStamp = projection.format; context.terrainConversions++;
        context.projections.put(chunk, projection);
        chunk.isTerrainPopulated=true;
    }

    /** Forge calls this on the main thread before deferred tile entities and chunk activation. */
    public static void beforeEntities(World world, NBTTagCompound data, Chunk chunk) {
        if (world.isRemote) return;
        Context context = world instanceof WorldServer ? context((WorldServer)world) : null;
        try {
            // Packed terrain is native save data, including when streaming is disabled.
            if (context == null) { CompactUnknowns.load(chunk, data); return; }
            ChunkKey key = new ChunkKey(chunk.xPosition, chunk.zPosition);
            if (data.hasKey(PROJECTION, 8)) {
                String raw = data.getString(PROJECTION);
                if (raw.length() > 4 * 1024 * 1024) throw new IOException("Oversized chunk projection");
                Projection saved = JSON.fromJson(raw, Projection.class);
                if (!context.detached.containsKey(key) && reusable(context, saved, context.store.revision(key))) {
                    CompactUnknowns.load(chunk,data);
                    context.projections.put(chunk, saved); context.nativeCacheHits++; return;
                }
            }
            ChunkStore.Versioned value = canonical(context, key);
            if (value != null) {
                applyCanonical(context, chunk, value);
                // The prepared tile entities are already installed; discard the stale delayed list.
                data.setTag("TileEntities", new net.minecraft.nbt.NBTTagList());
            } else {
                Projection projection = null;
                if (data.hasKey(PROJECTION, 8)) {
                    String raw = data.getString(PROJECTION);
                    if (raw.length() > 4 * 1024 * 1024) throw new IOException("Oversized chunk projection");
                    projection = JSON.fromJson(raw, Projection.class);
                    if (projection == null || !context.binding.worldId.equals(projection.worldId) || projection.revision != 0)
                        throw new IOException("Missing or mismatched chunk authority");
                }
                CompactUnknowns.load(chunk,data);
                if (projection == null) projection = initialProjection(context.binding.worldId, key, context.legacyResidual);
                projection.baselineEligible = context.world.equals(Paths.get(context.binding.sourceWorld));
                context.projections.put(chunk, projection);
            }
        } catch (Exception error) { if (context != null) context.failure = error; throw new LoadFailure(error); }
    }

    public static void dirty(Chunk chunk) {
        CompactUnknowns.changed(chunk);
        if (!(chunk.worldObj instanceof WorldServer)) return;
        Context context = WORLDS.get((WorldServer)chunk.worldObj);
        if (context != null && !context.applying) {
            Projection projection = context.projections.get(chunk); if (projection != null) projection.cacheStamp = null;
            if (context.writes != null) context.changed.add(new ChunkKey(chunk.xPosition, chunk.zPosition));
        }
    }

    @cpw.mods.fml.common.eventhandler.SubscribeEvent
    public void saved(net.minecraftforge.event.world.ChunkDataEvent.Save event) {
        if (event.world.isRemote) return;
        Context context = event.world instanceof WorldServer ? WORLDS.get((WorldServer)event.world) : null;
        try {
            CompactUnknowns.save(event.getChunk(),event.getData().getCompoundTag("Level"));
            if (context == null) return;
            Chunk chunk = event.getChunk(); ChunkKey key = new ChunkKey(chunk.xPosition, chunk.zPosition);
            Projection projection = context.projections.get(chunk);
            if (projection == null) {
                projection = initialProjection(context.binding.worldId, key, context.legacyResidual);
                context.projections.put(chunk, projection);
                if (context.loaded.size() >= MAX_LOADED && !context.loaded.containsKey(key)) throw new IOException("Native resident chunk budget exhausted");
                context.loaded.put(key, new Resident(chunk, projection));
                if (context.writes != null) context.changed.add(key);
            }
            context.sealNative(chunk, projection);
            context.drainChanges();
            if (projection != null) event.getData().getCompoundTag("Level").setString(PROJECTION, JSON.toJson(savedProjection(context, projection, new ChunkKey(event.getChunk().xPosition, event.getChunk().zPosition))));
        } catch (Exception error) { if (context != null) context.failure = error; throw new LoadFailure(error); }
    }

    @cpw.mods.fml.common.eventhandler.SubscribeEvent
    public void loaded(ChunkEvent.Load event) {
        if (!(event.world instanceof WorldServer) || !(event.getChunk() instanceof Chunk)) return;
        WorldServer level = (WorldServer)event.world; Chunk chunk = (Chunk)event.getChunk();
        Context context = context(level); if (context == null) return;
        try {
            if (context.loaded.size() >= MAX_LOADED) throw new IOException("Native resident chunk budget exhausted");
            ChunkKey key = new ChunkKey(chunk.xPosition, chunk.zPosition);
            Projection projection = context.projections.get(chunk);
            if (projection == null) {
                projection = initialProjection(context.binding.worldId, key, context.legacyResidual);
                context.projections.put(chunk, projection);
            }
            context.sealNative(chunk, projection);
            context.loaded.put(key, new Resident(chunk, projection));
            if (context.writes != null && projection.revision == 0) context.changed.add(key);
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    @cpw.mods.fml.common.eventhandler.SubscribeEvent
    public void unloaded(ChunkEvent.Unload event) {
        if (!event.world.isRemote) CompactUnknowns.unload(event.getChunk());
        if (!(event.world instanceof WorldServer) || !(event.getChunk() instanceof Chunk)) return;
        WorldServer level = (WorldServer)event.world; Chunk chunk = (Chunk)event.getChunk();
        Context context = WORLDS.get(level); if (context == null) return;
        try {
            ChunkKey key = new ChunkKey(chunk.xPosition, chunk.zPosition);
            context.detach(key);
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    static void serviceRequests(WorldServer level) throws Exception {
        Context context = WORLDS.get(level);
        if (context == null || !context.world.equals(Paths.get(context.binding.sourceWorld))) return;
        Path directory = Paths.get(context.binding.authority).resolve("requests");
        for (SourceChunkRequests.Request request : SourceChunkRequests.poll(directory, context.binding.worldId)) try {
            ChunkStore.Versioned existing = context.store.read(request.key());
            if (existing != null) { SourceChunkRequests.respond(directory, request, existing.chunk); continue; }
            if (!context.sourceContains(request.key()) && !context.loaded.containsKey(request.key()))
                throw new IOException("Requested source chunk has not been generated");
            Chunk chunk = level.getChunkFromChunkCoords(request.chunkX, request.chunkZ);
            Resident resident = context.loaded.get(request.key());
            ChunkSnapshot captured = TerrainTransfer.capture(level, chunk, resident == null ? null : resident.projection.residual);
            if (context.writes != null) {
                context.changed.add(request.key()); context.flushKey(request.key());
            } else {
                ChunkStore.Lease owner = context.store.lease();
                if (context.lease != null && owner.epoch == context.lease.epoch && owner.owner.equals(context.instance)) {
                    long revision = context.saves.commit(context.store, context.lease, Collections.singletonList(new ChunkStore.Write(0, captured)));
                    if (resident != null) resident.projection.revision = revision;
                }
            }
            SourceChunkRequests.respond(directory, request, captured);
        } catch (Exception error) { SourceChunkRequests.reject(directory, request, error); }
    }

    static void tick(WorldServer level) throws Exception {
        Context context = context(level); if (context == null) return;
        if (!net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().playerEntityList.isEmpty()) context.player = (net.minecraft.entity.player.EntityPlayerMP)net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
        context.destination.tick();
        ClientHooks.bridge().diagnostics.put("destination",context.destination.diagnostics());
        context.drainChanges();
        if (context.writes != null) context.writes.tick(context::capture, context.detached.isEmpty() ? 2 : 16, TimeUnit.MILLISECONDS.toNanos(3));
        ClientHooks.bridge().diagnostics.put("streamingProtocol", StreamingMode.PROTOCOL);
        ClientHooks.bridge().diagnostics.put("streamingWorldId", context.binding.worldId);
        ClientHooks.bridge().diagnostics.put("streamingBinding", context.world.resolve("epoch/authority.json").toString());
        ClientHooks.bridge().diagnostics.put("streamingLoaded", context.loaded.size());
        ClientHooks.bridge().diagnostics.put("nativeTerrainCacheHits", context.nativeCacheHits);
        ClientHooks.bridge().diagnostics.put("terrainConversions", context.terrainConversions);
        ClientHooks.bridge().diagnostics.put("authorityCache", context.store.diagnostics());
        ClientHooks.bridge().diagnostics.put("terrainTimings", TerrainMetrics.diagnostics());
        ClientHooks.bridge().diagnostics.put("streamingDirty", context.writes == null ? 0 : context.writes.pendingCount());
        if (context.player != null && context.ticks % 20 == 0) {
            int tileEntities = 0;
            for (Resident resident : context.loaded.values()) tileEntities += resident.chunk.chunkTileEntityMap.size();
            ClientHooks.bridge().diagnostics.put("streamingTileEntities", tileEntities);
            ClientHooks.bridge().diagnostics.put("nativeSaveQueue", NativeSaveQueue.diagnostics());
            int unpopulated=0, unlit=0;
            for (Object value : context.player.loadedChunks) {
                ChunkCoordIntPair pos=(ChunkCoordIntPair)value;
                Chunk chunk=(Chunk)level.theChunkProviderServer.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(pos.chunkXPos,pos.chunkZPos));
                if (chunk != null) { if (!chunk.isTerrainPopulated) unpopulated++; if (!chunk.isLightPopulated) unlit++; }
            }
            ClientHooks.bridge().diagnostics.put("streamingPendingChunks", context.player.loadedChunks.size());
            ClientHooks.bridge().diagnostics.put("streamingPendingUnpopulated", unpopulated);
            ClientHooks.bridge().diagnostics.put("streamingPendingUnlit", unlit);
        }
        ClientHooks.bridge().diagnostics.put("streamingTicks", ++context.ticks);
    }

    static void configureSnapshot(WorldServer level, Snapshot snapshot, double x, double z) {
        if (!enabled(level)) return;
        ChunkKey center = ChunkKey.at(x, z);
        snapshot.sizeX = snapshot.sizeZ = 16;
        snapshot.x0 = center.x * 16;
        snapshot.z0 = center.z * 16;
    }

    static String[] restoreBiomes(WorldServer level, Snapshot bounds, String[] nativeCells, WorldExtras.Carry legacy) throws Exception {
        Context context = context(level);
        if (context == null) return legacy == null ? nativeCells : legacy.restore(bounds, nativeCells);
        String[] restored = nativeCells.clone();
        for (int cz = bounds.minChunkZ(); cz <= bounds.maxChunkZ(); cz++) for (int cx = bounds.minChunkX(); cx <= bounds.maxChunkX(); cx++) {
            Projection projection = context.projections.get(level.getChunkFromChunkCoords(cx, cz));
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


    static boolean reuseTerrain(WorldServer level, Snapshot snapshot) throws Exception {
        Context context = context(level); if (context == null) return false;
        context.flush();
        ChunkStore.Versioned content = context.store.read(new ChunkKey(snapshot.x0 / 16, snapshot.z0 / 16));
        if (content == null) throw new IOException("Player terrain has not been committed");
        ChunkSnapshot chunk = content.chunk;
        snapshot.palette = chunk.palette; snapshot.runs = chunk.runs;
        snapshot.blockEntities = chunk.blockEntities; snapshot.appearanceSources = chunk.appearanceSources;
        snapshot.sourceInstance = chunk.sourceInstance;
        return true;
    }

    static void writeCarry(WorldServer level, Path file, WorldExtras.Carry carry) throws IOException {
        Context context = context(level);
        if (context == null) WorldExtras.writeCarry(file, carry); else context.saves.writeCarry(file, carry);
    }

    static Snapshot readSnapshot(WorldServer level, Path snapshot) throws IOException {
        Context context = context(level);
        return context == null ? Snapshot.read(snapshot) : context.saves.read(snapshot);
    }

    static Path restart(WorldServer level) {
        Context context = context(level); return context == null ? null : context.restart;
    }

    static void restarted(WorldServer level) {
        Context context = context(level); if (context != null) context.restart = null;
    }

    @cpw.mods.fml.common.eventhandler.SubscribeEvent
    public void playerSaved(net.minecraftforge.event.entity.player.PlayerEvent.SaveToFile event) {
        if (!(event.entityPlayer instanceof net.minecraft.entity.player.EntityPlayerMP)) return;
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP)event.entityPlayer;
        Context context = WORLDS.get((WorldServer)player.worldObj);
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
        Snapshot snapshot = Handoff.instance.exportWorld(net.minecraft.server.MinecraftServer.getServer(), context.player);
        context.saves.submit(snapshot, context.binding, context.lease, context.store.checkpoint(context.lease));
    }

    static void exported(WorldServer level, Snapshot data, Path snapshot) throws Exception {
        Context context = context(level);
        if (context == null) { data.write(snapshot); Files.deleteIfExists(StreamCheckpoint.path(snapshot)); return; }
        if (context.writes == null) throw new IOException("Frozen standby cannot export as the authoritative writer");
        context.destination.close();
        context.flush(); context.writes.close(); context.writes = null;
        context.saves.export(data, snapshot, context.binding, context.lease, context.store.checkpoint(context.lease));
    }

    static void verifyImport(WorldServer level, Path snapshot) throws Exception {
        Context context = context(level);
        if (context == null) {
            if (Files.exists(StreamCheckpoint.path(snapshot))) throw new IOException("This instance has not enabled the streaming protocol");
            return;
        }
        if (context.writes != null) throw new IOException("Cannot overwrite a running authority writer");
        context.imported = context.saves.verify(snapshot, context.binding, context.store, snapshot.equals(context.restart));
        context.destination.close();
    }

    static void resume(WorldServer level) throws Exception {
        Context context = context(level); if (context == null) return;
        if (context.failure != null) throw new IOException("Streaming context requires recovery", context.failure);
        if (context.writes != null) {
            WorldSession.requireWriter(context.store, context.lease, context.instance); return;
        }
        long expected = WorldSession.prepareResume(context.store, context.lease, context.imported, context.instance);
        for (Resident resident : new ArrayList<>(context.loaded.values())) {
            ChunkKey key = new ChunkKey(resident.chunk.xPosition, resident.chunk.zPosition);
            long latestRevision = context.store.revision(key);
            if (latestRevision == 0 || latestRevision == resident.projection.revision) continue;
            ChunkStore.Versioned latest = context.reads.read(context.store, key);
            if (latest == null) throw new IOException("Committed terrain disappeared during reconciliation");
            TerrainTransfer.Prepared prepared = TerrainTransfer.prepare(level, latest.chunk);
            context.applying = true;
            try { TerrainTransfer.apply(level, resident.chunk, prepared); } finally { context.applying = false; }
            resident.projection.revision = latest.revision; resident.projection.residual = prepared.residual();
            resident.projection.format = ProjectionCache.stamp(Epoch.class); resident.projection.cacheStamp = resident.projection.format; context.terrainConversions++;
            resident.chunk.func_150809_p();
        }
        // Lease publication is the handoff commit point. Everything above remains recoverable by the source.
        context.becomeWriter(WorldSession.commitResume(context.store, expected, context.instance)); context.imported = null;
        for (Map.Entry<ChunkKey, Resident> entry : context.loaded.entrySet())
            if (entry.getValue().projection.revision == 0) context.changed.add(entry.getKey());
    }

    static void flush(WorldServer level) throws Exception {
        Context context = WORLDS.get(level); if (context != null) context.flush();
    }

    public static void stopping() throws Exception {
        synchronized(WORLDS){
            for(Context context:WORLDS.values()){
                if (context.player != null && context.player.isEntityAlive()) saveRestart(context);
                context.destination.close();
                context.flush();
                if(context.writes!=null){context.writes.close();context.writes=null;}
            }
        }
    }

    public static void stopped() {
        AutoRuntime.worldStopped();
        ClientHooks.bridge().worldStopped();
        ClientHooks.resetWorld(); Handoff.instance.resetWorld();
        CompactUnknowns.clear();
        synchronized (WORLDS) {
            for (Iterator<Map.Entry<WorldServer, Context>> it = WORLDS.entrySet().iterator(); it.hasNext();) {
                Map.Entry<WorldServer, Context> entry = it.next();
                try { entry.getValue().session.close(entry.getValue().saves, entry.getValue().reads); }
                catch (IOException error) { ClientHooks.bridge().diagnostics.put("savedWorldError", error.toString()); }
                WorldExtras.forgetCarry(entry.getValue().world.resolve("epoch/environment.json.gz"));
                it.remove();
            }
        }
    }
}
