package dev.epoch;

import com.google.gson.Gson;
import dev.epoch.bridge.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/** Native load/save bridge. Enabled by default for ordinary single-player launches. */
public final class StreamingWorld {
    private static final String PROJECTION = "EpochTerrainProjection";
    private static final Gson JSON = new Gson();
    private static final Map<ServerLevel, Context> WORLDS = Collections.synchronizedMap(new IdentityHashMap<>());
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
        final LevelChunk chunk;
        final Projection projection;
        Resident(LevelChunk chunk, Projection projection) { this.chunk = chunk; this.projection = projection; }
    }
    private static final class LoadFailure extends RuntimeException {
        LoadFailure(Throwable cause) { super("Epoch refused unsafe terrain loading", cause); }
    }
    private static final class Context {
        final ServerLevel level;
        final Path world;
        final WorldBinding binding;
        WorldSession session;
        Path restart;
        net.minecraft.server.level.ServerPlayer player;
        final String instance;
        final ChunkStore store;
        final WorldExtras.Carry legacyResidual;
        StreamCheckpoint imported;
        final Map<ChunkKey, Resident> loaded = new LinkedHashMap<>();
        final Map<ChunkKey, ChunkSnapshot> detached = new LinkedHashMap<>();
        long detachedBytes;
        int peakDetached;
        final Map<LevelChunk, Projection> projections = new WeakHashMap<>();
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

        Context(ServerLevel level) throws Exception {
            try {
            this.level = level; destination = new DestinationLoader(level); world = level.getServer().getWorldPath(LevelResource.ROOT).toRealPath();
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

        void sealNative(LevelChunk chunk, Projection projection) throws Exception {
            if (projection.revision != 0) return;
            ChunkKey key = new ChunkKey(chunk.getPos().x, chunk.getPos().z);
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

    static CompletableFuture<Void> travel(net.minecraft.server.level.ServerPlayer player, double x, double y, double z) throws Exception {
        Context context = context(player.getLevel());
        if (context == null || context.writes == null) throw new IOException("Streaming terrain is not active in this instance");
        return context.destination.start(player,x,y,z,player.getYRot(),player.getXRot());
    }

    static CompletableFuture<Void> travelAction(net.minecraft.server.level.ServerPlayer player, double x, double y, double z,
            DestinationLoader.Arrival arrival) throws Exception {
        Context context=context(player.getLevel());
        if(context==null || context.writes==null)throw new IOException("Streaming terrain is not active in this instance");
        return context.destination.start(player,x,y,z,player.getYRot(),player.getXRot(),arrival);
    }

    static void cancelTravel(net.minecraft.server.level.ServerPlayer player) {
        Context context = WORLDS.get(player.getLevel());
        if(context!=null) context.destination.close();
        player.displayClientMessage(new net.minecraft.network.chat.TextComponent("已取消目的地加载"),true);
    }

    static void worldOpened(net.minecraftforge.event.world.WorldEvent.Load event) {
        if (!(event.getWorld() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || level.getServer().isDedicatedServer()) return;
        StreamingMode.requireBoundWorld(level.getServer().getWorldPath(LevelResource.ROOT));
        context(level);
    }

    static boolean enabled(ServerLevel level) {
        return AutoRuntime.streamingEnabled() && level.dimension() == Level.OVERWORLD && !level.getServer().isDedicatedServer();
    }

    private static Context context(ServerLevel level) {
        if (!enabled(level)) return null;
        synchronized (WORLDS) {
            Context context = WORLDS.get(level);
            if (context == null) try { context = new Context(level); WORLDS.put(level, context); }
            catch (Exception error) { throw new LoadFailure(error); }
            return context;
        }
    }

    private static final String CACHED_LIGHT = "EpochDeferredCachedLight";

    /** Keep native light out of vanilla's queue until the authority revision is rechecked. */
    static void deferCachedLighting(CompoundTag tag) {
        CompoundTag saved = new CompoundTag();
        saved.putBoolean("valid", tag.getBoolean("isLightOn"));
        net.minecraft.nbt.ListTag lights = new net.minecraft.nbt.ListTag();
        for (net.minecraft.nbt.Tag value : tag.getList("sections", 10)) {
            CompoundTag section = (CompoundTag) value, copy = new CompoundTag();
            copy.putByte("Y", section.getByte("Y"));
            for (String name : new String[]{"BlockLight", "SkyLight"}) {
                if (section.contains(name, 7)) copy.put(name, section.get(name));
                section.remove(name);
            }
            lights.add(copy);
        }
        saved.put("sections", lights); tag.put(CACHED_LIGHT, saved); tag.putBoolean("isLightOn", false);
    }

    private static void restoreCachedLighting(ServerLevel level, LevelChunk chunk, CompoundTag saved) {
        if (!saved.getBoolean("valid")) return;
        level.getChunkSource().getLightEngine().retainData(chunk.getPos(), true);
        for (net.minecraft.nbt.Tag value : saved.getList("sections", 10)) {
            CompoundTag section = (CompoundTag) value;
            net.minecraft.core.SectionPos pos = net.minecraft.core.SectionPos.of(chunk.getPos(), section.getByte("Y"));
            if (section.contains("BlockLight", 7)) level.getChunkSource().getLightEngine().queueSectionData(
                    net.minecraft.world.level.LightLayer.BLOCK, pos, new net.minecraft.world.level.chunk.DataLayer(section.getByteArray("BlockLight")), true);
            if (section.contains("SkyLight", 7)) level.getChunkSource().getLightEngine().queueSectionData(
                    net.minecraft.world.level.LightLayer.SKY, pos, new net.minecraft.world.level.chunk.DataLayer(section.getByteArray("SkyLight")), true);
        }
        chunk.setLightCorrect(true);
    }

    /** Called only for scheduleChunkLoad, never for the native save path's blocking existence read. */
    public static CompletableFuture<Optional<CompoundTag>> loading(CompletableFuture<Optional<CompoundTag>> nativeRead,
            ServerLevel level, ChunkPos pos) {
        Context context = context(level); if (context == null) return nativeRead;
        ChunkKey key = new ChunkKey(pos.x, pos.z);
        try {
            if (!level.getServer().isSameThread()) throw new IOException("Native load scheduling left server thread");
            if (context.detached.containsKey(key)) context.flushKey(key);
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
        return context.reads.<Optional<CompoundTag>, Optional<CompoundTag>>submit(key, nativeRead, nativeTag -> {
            try {
                if (context.store.discarded(key)) return Optional.empty();
                if (nativeTag.isPresent()) {
                    String raw = nativeTag.get().getString(PROJECTION);
                    if (raw.length() > 4 * 1024 * 1024) throw new IOException("Oversized chunk projection");
                    Projection saved = raw.isEmpty() ? null : JSON.fromJson(raw, Projection.class);
                    if (reusable(context, saved, context.store.revision(key))) {
                        deferCachedLighting(nativeTag.get()); return nativeTag;
                    }
                }
                ChunkStore.Versioned canonical = context.store.read(key);
                if (canonical == null && !context.world.equals(Paths.get(context.binding.sourceWorld)) && context.sourceContains(key)) {
                    ChunkSnapshot source = SourceChunkRequests.fetch(Paths.get(context.binding.authority).resolve("requests"), context.binding.worldId, key, 30000);
                    canonical = context.store.read(key);
                    if (canonical == null) {
                        ChunkStore.Lease writer = context.lease;
                        if (writer == null) throw new IOException("Source did not commit the standby's requested terrain");
                        try { context.saves.commit(context.store, writer, Collections.singletonList(new ChunkStore.Write(0, source))); }
                        catch (IOException race) { if (context.store.read(key) == null) throw race; }
                        canonical = context.store.read(key);
                    }
                }
                if (canonical == null && context.world.equals(Paths.get(context.binding.sourceWorld))) {
                    if (context.store.baselineCovered(key) && !context.store.baselineRecorded(key)) {
                        if (!nativeTag.isPresent()) throw new IOException("Original native chunk is missing before admission");
                        CompoundTag original = nativeTag.get();
                        String status = original.getString("Status");
                        if (status.equals("full") || status.equals("minecraft:full")) original.putBoolean("EpochInitialNative", true);
                        else context.store.markGenerated(key);
                    } else if (!context.store.baselineRecorded(key)) context.store.markGenerated(key);
                }
                if (canonical == null) return nativeTag;
                context.incoming.put(key, canonical);
                // An absent native file must not start a second version's terrain generator.
                CompoundTag tag = nativeTag.orElseGet(CompoundTag::new);
                tag.putString("Status", "minecraft:full"); tag.putInt("xPos", pos.x); tag.putInt("zPos", pos.z);
                tag.putBoolean("isLightOn", false);
                for (net.minecraft.nbt.Tag section : tag.getList("sections", 10)) {
                    ((CompoundTag)section).remove("BlockLight"); ((CompoundTag)section).remove("SkyLight");
                }
                return Optional.of(tag);
            } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
        }).thenApply(value -> value.map(tag -> tag.copy()));
    }

    public static CompoundTag loadingSync(CompoundTag nativeTag, ServerLevel level, ChunkPos pos) {
        try { return loading(CompletableFuture.completedFuture(Optional.ofNullable(nativeTag)), level, pos).get(30, TimeUnit.SECONDS).orElse(null); }
        catch (Exception error) { throw new LoadFailure(error); }
    }

    public static ProtoChunk decoded(ProtoChunk nativeChunk, ServerLevel level, ChunkPos pos, CompoundTag tag) {
        Context context = context(level); if (context == null) return nativeChunk;
        try {
            LevelChunk chunk = nativeChunk instanceof ImposterProtoChunk wrapper ? wrapper.getWrapped() : null;
            ChunkKey key = new ChunkKey(pos.x, pos.z);
            CompoundTag cachedLight = tag.getCompound(CACHED_LIGHT); tag.remove(CACHED_LIGHT);
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
            if (chunk != null && reusable(context, projection, context.store.revision(key))) {
                restoreCachedLighting(level, chunk, cachedLight);
                context.projections.put(chunk, projection); context.nativeCacheHits++;
                return new ImposterProtoChunk(chunk, false);
            }
            long latestRevision = context.store.revision(key);
            ChunkStore.Versioned refreshed = canonical != null && canonical.revision == latestRevision ? canonical : context.store.read(key);
            if (canonical != null && refreshed == null) throw new IOException("Committed chunk disappeared during loading");
            canonical = refreshed;
            if (canonical != null) {
                if (chunk == null) chunk = new LevelChunk(level, nativeChunk, null);
                TerrainTransfer.Prepared prepared = TerrainTransfer.prepare(level, canonical.chunk);
                context.applying = true;
                try { TerrainTransfer.apply(level, chunk, prepared); } finally { context.applying = false; }
                projection = new Projection(); projection.worldId = context.binding.worldId;
                projection.revision = canonical.revision; projection.residual = prepared.residual();
                projection.format = prepared.cacheStamp; projection.cacheStamp = projection.format; context.terrainConversions++;
            }
            if (chunk != null) {
                if (projection == null) projection = initialProjection(context.binding.worldId,
                        new ChunkKey(pos.x, pos.z), context.legacyResidual);
                projection.baselineEligible = canonical == null && tag.getBoolean("EpochInitialNative");
                tag.remove("EpochInitialNative");
                context.projections.put(chunk, projection);
                return new ImposterProtoChunk(chunk, false);
            }
            return nativeChunk;
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    public static CompoundTag encoded(CompoundTag tag, ServerLevel level, ChunkAccess access) {
        Context context = WORLDS.get(level);
        if (context == null || !(access instanceof LevelChunk chunk)) return tag;
        Projection projection = context.projections.get(chunk);
        try {
            ChunkKey key = new ChunkKey(chunk.getPos().x, chunk.getPos().z);
            if (projection == null) {
                projection = initialProjection(context.binding.worldId, key, context.legacyResidual);
                context.projections.put(chunk, projection);
                if (context.loaded.size() >= MAX_LOADED && !context.loaded.containsKey(key)) throw new IOException("Native resident chunk budget exhausted");
                context.loaded.put(key, new Resident(chunk, projection));
                if (context.writes != null) context.changed.add(key);
            }
            context.sealNative(chunk, projection);
            // Native files are caches. Record only acknowledged revision; pending edits stay queued.
            // Reload always resolves authority, and unload retains pending data independently.
            context.drainChanges(); tag.putString(PROJECTION, JSON.toJson(savedProjection(context, projection, new ChunkKey(chunk.getPos().x, chunk.getPos().z)))); return tag;
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    public static void dirty(ChunkAccess access, boolean dirty) {
        if (!dirty || !(access instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ServerLevel level)) return;
        Context context = WORLDS.get(level);
        if (context != null && !context.applying) {
            Projection projection = context.projections.get(chunk); if (projection != null) projection.cacheStamp = null;
            if (context.writes != null) context.changed.add(new ChunkKey(chunk.getPos().x, chunk.getPos().z));
        }
    }

    /** Vanilla otherwise turns arbitrary chunk-read failures into new empty/generatable chunks. */
    public static void rejectFallback(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof LoadFailure)
            throw new net.minecraft.ReportedException(net.minecraft.CrashReport.forThrowable(
                    new IllegalStateException("Authority chunk could not be restored; refusing regeneration", cause), "Epoch terrain load"));
    }

    static void loaded(ChunkEvent.Load event) {
        if (!(event.getWorld() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        Context context = context(level); if (context == null) return;
        try {
            if (context.loaded.size() >= MAX_LOADED) throw new IOException("Native resident chunk budget exhausted");
            ChunkKey key = new ChunkKey(chunk.getPos().x, chunk.getPos().z);
            Projection projection = context.projections.get(chunk);
            if (projection == null) {
                projection = initialProjection(context.binding.worldId, key, context.legacyResidual);
                context.projections.put(chunk, projection);
            }
            context.sealNative(chunk, projection);
            // A prepared native chunk can become FULL after the handoff's resident scan.
            // Reconcile at registration too: its early decode may predate the source's final commit.
            if (context.writes != null && context.writes.isPending(key)) context.flushKey(key);
            long revision = context.store.revision(key);
            ChunkStore.Versioned latest = revision == projection.revision ? null : context.store.read(key);
            if (latest != null && latest.revision != projection.revision) {
                TerrainTransfer.Prepared prepared = TerrainTransfer.prepare(level, latest.chunk);
                context.applying = true;
                try { TerrainTransfer.apply(level, chunk, prepared); } finally { context.applying = false; }
                projection.revision = latest.revision; projection.residual = prepared.residual();
                projection.format = prepared.cacheStamp; projection.cacheStamp = projection.format; context.terrainConversions++;
                level.getChunkSource().getLightEngine().lightChunk(chunk, false);
            }
            context.loaded.put(key, new Resident(chunk, projection));
            if (context.writes != null && projection.revision == 0) context.changed.add(key);
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    static void unloaded(ChunkEvent.Unload event) {
        if (!(event.getWorld() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        Context context = WORLDS.get(level); if (context == null) return;
        try {
            ChunkKey key = new ChunkKey(chunk.getPos().x, chunk.getPos().z);
            // Native mutation hooks already mark actual edits. Unload itself is not a terrain edit.
            // Keep the chunk reachable until any final dirty generation is durably acknowledged.
            context.detach(key);
        } catch (Exception error) { context.failure = error; throw new LoadFailure(error); }
    }

    static void serviceRequests(ServerLevel level) throws Exception {
        Context context = WORLDS.get(level);
        if (context == null || !context.world.equals(Paths.get(context.binding.sourceWorld))) return;
        Path directory = Paths.get(context.binding.authority).resolve("requests");
        for (SourceChunkRequests.Request request : SourceChunkRequests.poll(directory, context.binding.worldId)) try {
            ChunkStore.Versioned existing = context.store.read(request.key());
            if (existing != null) { SourceChunkRequests.respond(directory, request, existing.chunk); continue; }
            if (!context.sourceContains(request.key()) && !context.loaded.containsKey(request.key()))
                throw new IOException("Requested source chunk has not been generated");
            LevelChunk chunk = level.getChunk(request.chunkX, request.chunkZ);
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

    static void tick(ServerLevel level) throws Exception {
        Context context = context(level); if (context == null) return;
        if (!context.level.getServer().getPlayerList().getPlayers().isEmpty()) context.player = (net.minecraft.server.level.ServerPlayer)context.level.getServer().getPlayerList().getPlayers().get(0);
        context.destination.tick();
        Epoch.bridge().diagnostics.put("destination", context.destination.diagnostics());
        context.drainChanges();
        if (context.writes != null) context.writes.tick(context::capture, context.detached.isEmpty() ? 2 : 16, TimeUnit.MILLISECONDS.toNanos(3));
        Epoch.bridge().diagnostics.put("streamingProtocol", StreamingMode.PROTOCOL);
        Epoch.bridge().diagnostics.put("streamingWorldId", context.binding.worldId);
        Epoch.bridge().diagnostics.put("streamingBinding", context.world.resolve("epoch/authority.json").toString());
        Epoch.bridge().diagnostics.put("streamingLoaded", context.loaded.size());
        Epoch.bridge().diagnostics.put("nativeTerrainCacheHits", context.nativeCacheHits);
        Epoch.bridge().diagnostics.put("terrainConversions", context.terrainConversions);
        Epoch.bridge().diagnostics.put("authorityCache", context.store.diagnostics());
        Epoch.bridge().diagnostics.put("terrainTimings", TerrainMetrics.diagnostics());
        Epoch.bridge().diagnostics.put("streamingDirty", context.writes == null ? 0 : context.writes.pendingCount());
        Epoch.bridge().diagnostics.put("streamingDetached", context.detached.size());
        Epoch.bridge().diagnostics.put("streamingDetachedBytes", context.detachedBytes);
        Epoch.bridge().diagnostics.put("streamingTicks", ++context.ticks);
    }

    static void configureSnapshot(ServerLevel level, Snapshot snapshot, double x, double z) {
        if (!enabled(level)) return;
        ChunkKey center = ChunkKey.at(x, z);
        snapshot.sizeX = snapshot.sizeZ = 16;
        snapshot.x0 = center.x * 16;
        snapshot.z0 = center.z * 16;
    }

    static String[] restoreBiomes(ServerLevel level, Snapshot bounds, String[] nativeCells, WorldExtras.Carry legacy) throws Exception {
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


    static boolean reuseTerrain(ServerLevel level, Snapshot snapshot) throws Exception {
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

    static void writeCarry(ServerLevel level, Path file, WorldExtras.Carry carry) throws IOException {
        Context context = context(level);
        if (context == null) WorldExtras.writeCarry(file, carry); else context.saves.writeCarry(file, carry);
    }

    static Snapshot readSnapshot(ServerLevel level, Path snapshot) throws IOException {
        Context context = context(level);
        return context == null ? Snapshot.read(snapshot) : context.saves.read(snapshot);
    }

    static Path restart(ServerLevel level) {
        Context context = context(level); return context == null ? null : context.restart;
    }

    static void restarted(ServerLevel level) {
        Context context = context(level); if (context != null) context.restart = null;
    }

    static void playerSaved(net.minecraftforge.event.entity.player.PlayerEvent.SaveToFile event) {
        if (!(event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer)) return;
        net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer)event.getPlayer();
        Context context = WORLDS.get(player.getLevel());
        if (context == null) return;
        context.player = player;
        try { saveRestart(context); }
        catch (Exception error) {
            Epoch.bridge().diagnostics.put("savedWorldError", error.toString());
            error.printStackTrace();
        }
    }

    private static void saveRestart(Context context) throws Exception {
        if (context.writes == null || context.failure != null || context.restart != null || context.player == null) return;
        ChunkStore.Lease owner = context.store.lease();
        if (context.lease.epoch != owner.epoch || !context.instance.equals(owner.owner)) return;
        Snapshot snapshot = Epoch.instance.exportWorld(context.level.getServer(), context.player);
        context.saves.submit(snapshot, context.binding, context.lease, context.store.checkpoint(context.lease));
    }

    static void exported(ServerLevel level, Snapshot data, Path snapshot) throws Exception {
        Context context = context(level);
        if (context == null) { data.write(snapshot); Files.deleteIfExists(StreamCheckpoint.path(snapshot)); return; }
        if (context.writes == null) throw new IOException("Frozen standby cannot export as the authoritative writer");
        context.destination.close();
        context.flush(); context.writes.close(); context.writes = null;
        context.saves.export(data, snapshot, context.binding, context.lease, context.store.checkpoint(context.lease));
    }

    static void verifyImport(ServerLevel level, Path snapshot) throws Exception {
        Context context = context(level);
        if (context == null) {
            if (Files.exists(StreamCheckpoint.path(snapshot))) throw new IOException("This instance has not enabled the streaming protocol");
            return;
        }
        if (context.writes != null) throw new IOException("Cannot overwrite a running authority writer");
        context.imported = context.saves.verify(snapshot, context.binding, context.store, snapshot.equals(context.restart));
        context.destination.close();
    }

    static void resume(ServerLevel level) throws Exception {
        Context context = context(level); if (context == null) return;
        if (context.failure != null) throw new IOException("Streaming context requires recovery", context.failure);
        if (context.writes != null) {
            WorldSession.requireWriter(context.store, context.lease, context.instance); return;
        }
        long expected = WorldSession.prepareResume(context.store, context.lease, context.imported, context.instance);
        List<CompletableFuture<?>> lighting = new ArrayList<>();
        for (Resident resident : new ArrayList<>(context.loaded.values())) {
            ChunkKey key = new ChunkKey(resident.chunk.getPos().x, resident.chunk.getPos().z);
            long latestRevision = context.store.revision(key);
            if (latestRevision == 0 || latestRevision == resident.projection.revision) continue;
            ChunkStore.Versioned latest = context.store.read(key);
            if (latest == null) throw new IOException("Committed terrain disappeared during reconciliation");
            TerrainTransfer.Prepared prepared = TerrainTransfer.prepare(level, latest.chunk);
            context.applying = true;
            try { TerrainTransfer.apply(level, resident.chunk, prepared); } finally { context.applying = false; }
            resident.projection.revision = latest.revision; resident.projection.residual = prepared.residual();
            resident.projection.format = prepared.cacheStamp; resident.projection.cacheStamp = resident.projection.format; context.terrainConversions++;
            lighting.add(level.getChunkSource().getLightEngine().lightChunk(resident.chunk, false));
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
        context.becomeWriter(WorldSession.commitResume(context.store, expected, context.instance)); context.imported = null;
        for (Map.Entry<ChunkKey, Resident> entry : context.loaded.entrySet())
            if (entry.getValue().projection.revision == 0) context.changed.add(entry.getKey());
    }

    static void flush(ServerLevel level) throws Exception {
        Context context = WORLDS.get(level); if (context != null) context.flush();
    }

    static void stopping(ServerStoppingEvent event) {
        synchronized (WORLDS) {
            for (Map.Entry<ServerLevel, Context> entry : WORLDS.entrySet()) {
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

    static void stopped(ServerStoppedEvent event) {
        AutoRuntime.worldStopped();
        Epoch.bridge().worldStopped();
        Epoch.instance.resetWorld();
        synchronized (WORLDS) {
            for (Iterator<Map.Entry<ServerLevel, Context>> it = WORLDS.entrySet().iterator(); it.hasNext();) {
                Map.Entry<ServerLevel, Context> entry = it.next();
                if (entry.getKey().getServer() != event.getServer()) continue;
                try { entry.getValue().session.close(entry.getValue().saves, entry.getValue().reads); }
                catch (IOException error) { Epoch.bridge().diagnostics.put("savedWorldError", error.toString()); }
                WorldExtras.forgetCarry(entry.getValue().world.resolve("epoch/environment.json.gz"));
                it.remove();
            }
        }
    }
}
