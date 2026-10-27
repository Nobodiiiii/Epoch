package dev.epoch;

import dev.epoch.bridge.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;

/** Terrain-only adapter. Never teleports a player or changes weather, clocks or inventory. */
final class TerrainTransfer {
    private static final Map<BlockState,String> STATE_NAMES = new IdentityHashMap<>();
    private static synchronized String stateName(BlockState state) {
        String name = STATE_NAMES.get(state);
        if (name == null) {
            name = NbtUtils.writeBlockState(state).toString();
            if (STATE_NAMES.size() >= 4096) STATE_NAMES.clear();
            STATE_NAMES.put(state, name);
        }
        return name;
    }

    private static java.lang.reflect.Field pendingEntities;
    @SuppressWarnings("unchecked")
    private static Map<BlockPos, CompoundTag> pending(LevelChunk chunk) throws Exception {
        if (pendingEntities == null) {
            for (String name : new String[]{"pendingBlockEntities", "f_187609_"}) try {
                pendingEntities = ChunkAccess.class.getDeclaredField(name); pendingEntities.setAccessible(true); break;
            } catch (NoSuchFieldException ignored) { }
            if (pendingEntities == null) throw new IOException("Missing native pending block entity storage");
        }
        return (Map<BlockPos, CompoundTag>) pendingEntities.get(chunk);
    }
    static final class Prepared {
        String cacheStamp;
        final ChunkSnapshot source;
        final BlockState[] cells;
        final Map<BlockPos, BlockEntity> entities;
        final Map<String, Holder<Biome>> biomes;
        final String[] appliedBiomes;
        Prepared(ChunkSnapshot source, BlockState[] cells, Map<BlockPos, BlockEntity> entities,
                Map<String, Holder<Biome>> biomes, String[] appliedBiomes) {
            this.source = source; this.cells = cells; this.entities = entities;
            this.biomes = biomes; this.appliedBiomes = appliedBiomes;
        }
        WorldExtras.Carry residual() {
            WorldExtras.Carry carry = new WorldExtras.Carry(); carry.bounds(source.blocks());
            carry.original = source.biomes; carry.applied = WorldExtras.Biomes.of(appliedBiomes);
            return carry;
        }
    }

    static ChunkSnapshot capture(ServerLevel level, LevelChunk chunk, WorldExtras.Carry residual) throws Exception {
        long started=System.nanoTime();
        try { return captureMeasured(level, chunk, residual); }
        finally { TerrainMetrics.record("capture",System.nanoTime()-started); }
    }

    private static ChunkSnapshot captureMeasured(ServerLevel level, LevelChunk chunk, WorldExtras.Carry residual) throws Exception {
        if (!level.getServer().isSameThread()) throw new IOException("Terrain capture must run on the server thread");
        ChunkSnapshot out = new ChunkSnapshot(); out.chunkX = chunk.getPos().x; out.chunkZ = chunk.getPos().z;
        out.version = Epoch.VERSION;out.sourceInstance = dev.epoch.bridge.Appearance.instance();
        Map<BlockState, String> encoded = new IdentityHashMap<>();
        Map<String, Integer> palette = new HashMap<>(); List<Integer> runs = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(); int previous = -1, count = 0;
        Snapshot bounds = out.blocks();
        for (int index = 0; index < ChunkSnapshot.VOLUME; index++) {
            int y = index / 256;
            if ((index & 4095) == 0 && chunk.getSection(chunk.getSectionIndex(y)).hasOnlyAir()
                    && !chunk.getSection(chunk.getSectionIndex(y)).getStates().maybeHas(state -> state != net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())) {
                String air = stateName(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                Integer id = palette.get(air);
                if (id == null) { id = palette.size(); palette.put(air, id); out.palette.add(air); }
                if (id == previous) count += 4096;
                else { if (count > 0) { runs.add(previous); runs.add(count); } previous = id; count = 4096; }
                index += 4095;
                continue;
            }
            pos.set(bounds.blockX(index), bounds.blockY(index), bounds.blockZ(index));
            BlockState state = chunk.getSection(chunk.getSectionIndex(y)).getBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15);
            String canonical = encoded.computeIfAbsent(state, TerrainTransfer::stateName);
            BlockEntity entity = state.hasBlockEntity() ? chunk.getBlockEntity(pos) : null;
            String payload = entity == null ? null : entity.saveWithFullMetadata().toString();
            if (entity instanceof Epoch.UnknownEntity unknown && !unknown.originalState.isEmpty()) {
                canonical = unknown.originalState;out.appearanceSources.put(Integer.toString(index), unknown.sourceInstance); payload = unknown.originalNbt.isEmpty() ? null : unknown.originalNbt;
            }
            if (payload != null) out.blockEntities.put(Integer.toString(index), payload);
            Integer id = palette.get(canonical);
            if (id == null) { id = palette.size(); palette.put(canonical, id); out.palette.add(canonical); }
            if (id == previous) count++;
            else { if (count > 0) { runs.add(previous); runs.add(count); } previous = id; count = 1; }
        }
        runs.add(previous); runs.add(count); out.runs = runs.stream().mapToInt(Integer::intValue).toArray();
        String[] cells = new String[ChunkSnapshot.VOLUME / 4];
        // Native biome cells cover 4x4 columns; sample once and expand to the existing wire grid.
        for (int y = 0; y < 64; y++) for (int z = 0; z < 16; z += 4) for (int x = 0; x < 16; x += 4) {
            String biome = chunk.getNoiseBiome((bounds.x0 + x) >> 2, y, (bounds.z0 + z) >> 2)
                    .unwrapKey().orElseThrow(() -> new IllegalStateException("Unregistered biome")).location().toString();
            for (int dz = 0; dz < 4; dz++) Arrays.fill(cells, (y * 16 + z + dz) * 16 + x,
                    (y * 16 + z + dz) * 16 + x + 4, biome);
        }
        out.biomes = WorldExtras.Biomes.of(residual == null ? cells : residual.restore(bounds, cells));
        dev.epoch.bridge.Appearance.request(out.palette); out.validate(); return out;
    }

    /** Resolve every state and validate all payloads before touching the destination chunk. */
    static Prepared prepare(ServerLevel level, ChunkSnapshot source) throws Exception {
        long started=System.nanoTime();
        try { return prepareMeasured(level, source); }
        finally { TerrainMetrics.record("prepare",System.nanoTime()-started); }
    }

    private static Prepared prepareMeasured(ServerLevel level, ChunkSnapshot source) throws Exception {
        source.validate(); source = BlockPayloads.project(source, Epoch.VERSION); source = BlockContext.repairLeaves(source); Snapshot bounds = source.blocks();
        NativeStates.Context context = NativeStates.context();
        BlockStateCompatibility.Report report = new BlockStateCompatibility.Report(source.version, Epoch.VERSION);
        BlockState[] palette = new BlockState[source.palette.size()];
        for (int n = 0; n < palette.length; n++) palette[n] = context.decode(source.palette.get(n), report, n);
        BlockState[] cells = new BlockState[ChunkSnapshot.VOLUME];
        Map<BlockPos, BlockEntity> entities = new HashMap<>(); int index = 0;
        BitSet payloadPositions = new BitSet(ChunkSnapshot.VOLUME);
        for (String position : source.blockEntities.keySet()) payloadPositions.set(Integer.parseInt(position));
        for (int r = 0; r < source.runs.length; r += 2) {
            int id = source.runs[r];
            int end = index + source.runs[r + 1], nextPayload = payloadPositions.nextSetBit(index);
            if (palette[id] != null && !palette[id].hasBlockEntity() && (nextPayload < 0 || nextPayload >= end)) {
                Arrays.fill(cells, index, end, palette[id]); index = end; continue;
            }
            for (int n = 0; n < source.runs[r + 1]; n++, index++) {
                BlockPos pos = new BlockPos(bounds.blockX(index), bounds.blockY(index), bounds.blockZ(index));
                BlockState state = palette[id]; String raw = source.blockEntities.get(Integer.toString(index));
                BlockEntity entity = null; boolean unknown = state == null;
                if (!unknown) try {
                    if (state.hasBlockEntity()) {
                        if (!(state.getBlock() instanceof EntityBlock factory)) throw new IOException("Missing entity factory");
                        entity = factory.newBlockEntity(pos, state);
                        if (entity == null) throw new IOException("Empty entity factory");
                    }
                    if (raw != null) {
                        if (entity == null) throw new IOException("Payload on non-entity block");
                        CompoundTag tag = TagParser.parseTag(raw);
                        if (!BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString().equals(tag.getString("id")))
                            throw new IOException("Incompatible entity type");
                        tag.putInt("x", pos.getX()); tag.putInt("y", pos.getY()); tag.putInt("z", pos.getZ());
                        entity.load(tag.copy());
                        if (!Epoch.preservesNbt(tag, entity.saveWithFullMetadata())) throw new IOException("Entity payload would lose data");
                    }
                } catch (Exception incompatible) { unknown = true; }
                if (unknown) {
                    state = Epoch.UNKNOWN.get().defaultBlockState();
                    Epoch.UnknownEntity placeholder = new Epoch.UnknownEntity(pos, state);
                    placeholder.originalState = source.originalState(index); placeholder.originalNbt = source.originalPayload(index);
                    placeholder.sourceVersion = source.version;placeholder.sourceInstance = source.appearanceSource(index); entity = placeholder;
                }
                cells[index] = state;
                if (entity != null) entities.put(pos, entity);
            }
        }
        Map<String, Holder<Biome>> biomes = EnvironmentTransfer.registry(level);
        String[] applied = BiomeCompatibility.project(source.biomes.expand(16384), 16, 64, 16, true, biomes.keySet());
        Prepared result = new Prepared(source, cells, entities, biomes, applied);
        result.cacheStamp = ProjectionCache.stamp(Epoch.class, context.rules.fingerprint);
        return result;
    }

    /** Caller must hold the chunk behind the load barrier and finish lighting before release. */
    static void apply(ServerLevel level, LevelChunk chunk, Prepared prepared) throws Exception {
        long started=System.nanoTime();
        try { applyMeasured(level, chunk, prepared); }
        finally { TerrainMetrics.record("apply",System.nanoTime()-started); }
    }

    private static void applyMeasured(ServerLevel level, LevelChunk chunk, Prepared prepared) throws Exception {
        if (!level.getServer().isSameThread()) throw new IOException("Terrain apply must run on the server thread");
        if (chunk.getPos().x != prepared.source.chunkX || chunk.getPos().z != prepared.source.chunkZ)
            throw new IOException("Prepared terrain belongs to a different chunk");
        Map<BlockPos, CompoundTag> pending = pending(chunk);
        for (BlockPos pos : new HashSet<>(chunk.getBlockEntitiesPos())) if (pos.getY() >= 0 && pos.getY() < 256) {
            chunk.removeBlockEntity(pos);
            // Native removeBlockEntity leaves packed NBT and, before activation, the live map.
            // Both must be cleared or a deleted container can be promoted again later.
            pending.remove(pos);
            BlockEntity removed = chunk.getBlockEntities().remove(pos);
            if (removed != null) removed.setRemoved();
        }
        // Direct section writes deliberately avoid placement callbacks against a half-built world.
        for (int y = 0; y < 256; y++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                section.setBlockState(x, y & 15, z, prepared.cells[(y * 16 + z) * 16 + x]);
        }
        for (BlockEntity entity : prepared.entities.values()) chunk.addAndRegisterBlockEntity(entity);
        int x0 = prepared.source.chunkX * 16, z0 = prepared.source.chunkZ * 16;
        chunk.fillBiomesFromNoise((x, y, z, sampler) -> {
            if (y < 0 || y >= 64) return chunk.getNoiseBiome(x, y, z);
            return prepared.biomes.get(prepared.appliedBiomes[(y * 16 + z * 4 - z0) * 16 + x * 4 - x0]);
        }, null);
        Heightmap.primeHeightmaps(chunk, ChunkStatus.FULL.heightmapsAfter());
        chunk.setLightCorrect(false); chunk.setUnsaved(true);
    }
}
