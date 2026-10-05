package dev.epoch;

import dev.epoch.bridge.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.util.math.BlockPos;

import net.minecraft.util.registry.Registry;
import net.minecraft.nbt.*;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.biome.Biome;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.*;
import net.minecraft.world.gen.Heightmap;

/** Terrain-only adapter. Never teleports a player or changes weather, clocks or inventory. */
final class TerrainTransfer {
    private static final Map<BlockState,String> STATE_NAMES = new IdentityHashMap<>();
    private static synchronized String stateName(BlockState state) {
        String name = STATE_NAMES.get(state);
        if (name == null) {
            name = NBTUtil.writeBlockState(state).toString();
            if (STATE_NAMES.size() >= 4096) STATE_NAMES.clear();
            STATE_NAMES.put(state, name);
        }
        return name;
    }

    private static java.lang.reflect.Field pendingEntities;
    @SuppressWarnings("unchecked")
    static Map<BlockPos, CompoundNBT> pending(Chunk chunk) throws Exception {
        if (pendingEntities == null) {
            for (String name : new String[]{"pendingBlockEntities", "field_201618_i"}) try {
                pendingEntities = Chunk.class.getDeclaredField(name); pendingEntities.setAccessible(true); break;
            } catch (NoSuchFieldException ignored) { }
            if (pendingEntities == null) throw new IOException("Missing native pending block entity storage");
        }
        return (Map<BlockPos, CompoundNBT>) pendingEntities.get(chunk);
    }
    static final class Prepared {
        final ChunkSnapshot source;
        final BlockState[] cells;
        final Map<BlockPos, TileEntity> entities;
        final Map<String, Biome> biomes;
        final String[] appliedBiomes;
        Prepared(ChunkSnapshot source, BlockState[] cells, Map<BlockPos, TileEntity> entities,
                Map<String, Biome> biomes, String[] appliedBiomes) {
            this.source = source; this.cells = cells; this.entities = entities;
            this.biomes = biomes; this.appliedBiomes = appliedBiomes;
        }
        WorldExtras.Carry residual() {
            WorldExtras.Carry carry = new WorldExtras.Carry(); carry.bounds(source.blocks());
            carry.original = source.biomes; carry.applied = WorldExtras.Biomes.of(appliedBiomes);
            return carry;
        }
    }

    static ChunkSnapshot capture(ServerWorld level, Chunk chunk, WorldExtras.Carry residual) throws Exception {
        if (!level.getServer().isSameThread()) throw new IOException("Terrain capture must run on the server thread");
        ChunkSnapshot out = new ChunkSnapshot(); out.chunkX = chunk.getPos().x; out.chunkZ = chunk.getPos().z;
        out.version = Epoch.VERSION;
        Map<BlockState, String> encoded = new IdentityHashMap<>();
        Map<String, Integer> palette = new HashMap<>(); List<Integer> runs = new ArrayList<>();
        BlockPos.Mutable pos = new BlockPos.Mutable(); int previous = -1, count = 0;
        Snapshot bounds = out.blocks();
        for (int index = 0; index < ChunkSnapshot.VOLUME; index++) {
            int y = index / 256;
            ChunkSection section = chunk.getSections()[y >> 4];
            if ((index & 4095) == 0 && (section == null || (section.isEmpty()
                    && !section.getStates().maybeHas(state -> state != net.minecraft.block.Blocks.AIR.defaultBlockState())))) {
                String air = stateName(net.minecraft.block.Blocks.AIR.defaultBlockState());
                Integer id = palette.get(air);
                if (id == null) { id = palette.size(); palette.put(air, id); out.palette.add(air); }
                if (id == previous) count += 4096;
                else { if (count > 0) { runs.add(previous); runs.add(count); } previous = id; count = 4096; }
                index += 4095;
                continue;
            }
            pos.set(bounds.blockX(index), bounds.blockY(index), bounds.blockZ(index));
            BlockState state = section.getBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15);
            String canonical = encoded.computeIfAbsent(state, TerrainTransfer::stateName);
            TileEntity entity = state.hasTileEntity() ? chunk.getBlockEntity(pos) : null;
            String payload = entity == null ? null : entity.save(new CompoundNBT()).toString();
            if (entity instanceof Epoch.UnknownTile && !((Epoch.UnknownTile)entity).originalState.isEmpty()) {
                Epoch.UnknownTile unknown = (Epoch.UnknownTile)entity;
                canonical = unknown.originalState; payload = unknown.originalNbt.isEmpty() ? null : unknown.originalNbt;
            }
            if (payload != null) out.blockEntities.put(Integer.toString(index), payload);
            Integer id = palette.get(canonical);
            if (id == null) { id = palette.size(); palette.put(canonical, id); out.palette.add(canonical); }
            if (id == previous) count++;
            else { if (count > 0) { runs.add(previous); runs.add(count); } previous = id; count = 1; }
        }
        runs.add(previous); runs.add(count); out.runs = runs.stream().mapToInt(Integer::intValue).toArray();
        String[] cells = new String[ChunkSnapshot.VOLUME / 4];
        // Native biome cells cover 4x4 columns; expand each sample to the shared wire grid.
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        for (int y = 0; y < 64; y++) for (int z = 0; z < 16; z += 4) for (int x = 0; x < 16; x += 4) {
            String biome = biomes.getKey(chunk.getBiomes().getNoiseBiome((bounds.x0 + x) >> 2, y, (bounds.z0 + z) >> 2)).toString();
            for (int dz = 0; dz < 4; dz++) Arrays.fill(cells, (y * 16 + z + dz) * 16 + x,
                    (y * 16 + z + dz) * 16 + x + 4, biome);
        }
        out.biomes = WorldExtras.Biomes.of(residual == null ? cells : residual.restore(bounds, cells));
        out.validate(); return out;
    }

    /** Resolve every state and validate all payloads before touching the destination chunk. */
    static Prepared prepare(ServerWorld level, ChunkSnapshot source) throws Exception {
        source.validate(); Snapshot bounds = source.blocks();
        NativeStates.Context context = NativeStates.context();
        BlockStateCompatibility.Report report = new BlockStateCompatibility.Report(source.version, Epoch.VERSION);
        BlockState[] palette = new BlockState[source.palette.size()];
        for (int n = 0; n < palette.length; n++) palette[n] = context.decode(source.palette.get(n), report, n);
        BlockState[] cells = new BlockState[ChunkSnapshot.VOLUME];
        Map<BlockPos, TileEntity> entities = new HashMap<>(); int index = 0;
        for (int r = 0; r < source.runs.length; r += 2) {
            int id = source.runs[r];
            for (int n = 0; n < source.runs[r + 1]; n++, index++) {
                BlockPos pos = new BlockPos(bounds.blockX(index), bounds.blockY(index), bounds.blockZ(index));
                BlockState state = palette[id]; String raw = source.blockEntities.get(Integer.toString(index));
                TileEntity entity = null; boolean unknown = state == null;
                if (!unknown) try {
                    if (state.hasTileEntity()) {
                        entity = state.createTileEntity(level);
                        if (entity == null) throw new IOException("Empty entity factory");
                    }
                    if (raw != null) {
                        if (entity == null) throw new IOException("Payload on non-entity block");
                        CompoundNBT tag = JsonToNBT.parseTag(raw);
                        if (!net.minecraftforge.registries.ForgeRegistries.TILE_ENTITIES.getKey(entity.getType()).toString().equals(tag.getString("id")))
                            throw new IOException("Incompatible entity type");
                        tag.putInt("x", pos.getX()); tag.putInt("y", pos.getY()); tag.putInt("z", pos.getZ());
                        entity.load(state, tag.copy());
                        if (!Handoff.preservesNbt(tag, entity.save(new CompoundNBT()))) throw new IOException("Entity payload would lose data");
                    }
                } catch (Exception incompatible) { unknown = true; }
                if (unknown) {
                    state = Epoch.UNKNOWN.get().defaultBlockState();
                    Epoch.UnknownTile placeholder = new Epoch.UnknownTile();
                    placeholder.originalState = source.palette.get(id); placeholder.originalNbt = raw == null ? "" : raw;
                    placeholder.sourceVersion = source.version; entity = placeholder;
                }
                cells[index] = state;
                if (entity != null) { entity.setPosition(pos); entities.put(pos, entity); }
            }
        }
        Map<String, Biome> biomes = EnvironmentTransfer.registry(level);
        String[] applied = BiomeCompatibility.project(source.biomes.expand(16384), 16, 64, 16, true, biomes.keySet());
        return new Prepared(source, cells, entities, biomes, applied);
    }

    /** Caller must hold the chunk behind the load barrier and finish lighting before release. */
    static void apply(ServerWorld level, Chunk chunk, Prepared prepared) throws Exception {
        if (!level.getServer().isSameThread()) throw new IOException("Terrain apply must run on the server thread");
        if (chunk.getPos().x != prepared.source.chunkX || chunk.getPos().z != prepared.source.chunkZ)
            throw new IOException("Prepared terrain belongs to a different chunk");
        Map<BlockPos, CompoundNBT> pending = pending(chunk);
        for (BlockPos pos : new HashSet<>(chunk.getBlockEntitiesPos())) if (pos.getY() >= 0 && pos.getY() < 256) {
            chunk.removeBlockEntity(pos);
            // Native removeTileEntity leaves packed NBT and, before activation, the live map.
            // Both must be cleared or a deleted container can be promoted again later.
            pending.remove(pos);
            TileEntity removed = chunk.getBlockEntities().remove(pos);
            if (removed != null) removed.setRemoved();
        }
        // Direct section writes deliberately avoid placement callbacks against a half-built world.
        for (int y = 0; y < 256; y++) {
            ChunkSection section = chunk.getSections()[y >> 4];
            if (section == null) { section = new ChunkSection(y & ~15); chunk.getSections()[y >> 4] = section; }
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                section.setBlockState(x, y & 15, z, prepared.cells[(y * 16 + z) * 16 + x]);
        }
        for (TileEntity entity : prepared.entities.values()) chunk.addBlockEntity(entity);
        Biome[] nativeBiomes = (Biome[]) EnvironmentTransfer.biomeStorage().get(chunk.getBiomes());
        for (int y = 0; y < 64; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
            nativeBiomes[(y << 4) | (z << 2) | x] = prepared.biomes.get(prepared.appliedBiomes[(y * 16 + z * 4) * 16 + x * 4]);
        Heightmap.primeHeightmaps(chunk, ChunkStatus.FULL.heightmapsAfter());
        chunk.setLightCorrect(false); chunk.setUnsaved(true);
    }
}
