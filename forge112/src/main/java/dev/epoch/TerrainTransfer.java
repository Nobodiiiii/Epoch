package dev.epoch;

import dev.epoch.bridge.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.util.math.BlockPos;

import net.minecraft.util.ResourceLocation;
import net.minecraft.nbt.*;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.*;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Terrain-only adapter. Never teleports a player or changes weather, clocks or inventory. */
final class TerrainTransfer {
    private static final Map<IBlockState,String> STATE_NAMES = new IdentityHashMap<>();
    private static synchronized String stateName(IBlockState state) {
        String name = STATE_NAMES.get(state);
        if (name == null) {
            name = LegacyStates.encode(state);
            if (STATE_NAMES.size() >= 4096) STATE_NAMES.clear();
            STATE_NAMES.put(state, name);
        }
        return name;
    }

    static final class Prepared {
        final ChunkSnapshot source;
        final IBlockState[] cells;
        final Map<BlockPos, TileEntity> entities;
        final Map<String, Biome> biomes;
        final String[] appliedBiomes;
        Prepared(ChunkSnapshot source, IBlockState[] cells, Map<BlockPos, TileEntity> entities,
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

    static ChunkSnapshot capture(WorldServer level, Chunk chunk, WorldExtras.Carry residual) throws Exception {
        long started=System.nanoTime();
        try { return captureMeasured(level, chunk, residual); }
        finally { TerrainMetrics.record("capture",System.nanoTime()-started); }
    }

    private static ChunkSnapshot captureMeasured(WorldServer level, Chunk chunk, WorldExtras.Carry residual) throws Exception {
        if (!level.getMinecraftServer().isCallingFromMinecraftThread()) throw new IOException("Terrain capture must run on the server thread");
        ChunkSnapshot out = new ChunkSnapshot(); out.chunkX = chunk.x; out.chunkZ = chunk.z;
        out.version = Epoch.VERSION;out.sourceInstance = dev.epoch.bridge.Appearance.instance();
        Map<IBlockState, String> encoded = new IdentityHashMap<>();
        Map<String, Integer> palette = new HashMap<>(); List<Integer> runs = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(); int previous = -1, count = 0;
        Snapshot bounds = out.blocks();
        for (int index = 0; index < ChunkSnapshot.VOLUME; index++) {
            // Only absent storage is certainly ordinary air; modded air-like blocks still need capture.
            if ((index & 4095) == 0 && chunk.getBlockStorageArray()[index >> 12] == null) {
                String air = stateName(net.minecraft.init.Blocks.AIR.getDefaultState());
                Integer id = palette.get(air);
                if (id == null) { id = palette.size(); palette.put(air, id); out.palette.add(air); }
                if (id == previous) count += 4096;
                else { if (count > 0) { runs.add(previous); runs.add(count); } previous = id; count = 4096; }
                index += 4095;
                continue;
            }
            pos.setPos(bounds.blockX(index), bounds.blockY(index), bounds.blockZ(index));
            IBlockState state = chunk.getBlockState(pos);
            String canonical = encoded.computeIfAbsent(state, TerrainTransfer::stateName);
            TileEntity entity = state.getBlock().hasTileEntity(state) ? chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.IMMEDIATE) : null;
            String payload = entity == null ? null : LegacyItems.encodePayload(entity.writeToNBT(new NBTTagCompound())).toString();
            if (entity instanceof Epoch.UnknownTile && !((Epoch.UnknownTile)entity).originalState.isEmpty()) {
                Epoch.UnknownTile unknown = (Epoch.UnknownTile)entity;
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
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            String biome = Biome.REGISTRY.getNameForObject(chunk.getBiome(new BlockPos(bounds.x0 + x, 64, bounds.z0 + z), level.getBiomeProvider())).toString();
            for (int y = 0; y < 64; y++) cells[(y * 16 + z) * 16 + x] = biome;
        }
        out.biomes = WorldExtras.Biomes.of(residual == null ? cells : residual.restore(bounds, cells));
        LegacyContext.capture(out, level); dev.epoch.bridge.Appearance.request(out.palette); out.validate(); return out;
    }

    /** Resolve every state and validate all payloads before touching the destination chunk. */
    static Prepared prepare(WorldServer level, ChunkSnapshot source) throws Exception {
        long started=System.nanoTime();
        try { return prepareMeasured(level, source); }
        finally { TerrainMetrics.record("prepare",System.nanoTime()-started); }
    }

    private static Prepared prepareMeasured(WorldServer level, ChunkSnapshot source) throws Exception {
        source.validate(); source = BlockPayloads.project(source, Epoch.VERSION); source = BlockContext.repairPending(source); Snapshot bounds = source.blocks();
        IBlockState[] palette = new IBlockState[source.palette.size()];
        for (int n = 0; n < palette.length; n++) palette[n] = LegacyStates.decode(source.palette.get(n));
        IBlockState[] cells = new IBlockState[ChunkSnapshot.VOLUME];
        Map<BlockPos, TileEntity> entities = new HashMap<>(); int index = 0;
        BitSet payloadPositions = new BitSet(ChunkSnapshot.VOLUME);
        for (String position : source.blockEntities.keySet()) payloadPositions.set(Integer.parseInt(position));
        for (int r = 0; r < source.runs.length; r += 2) {
            int id = source.runs[r];
            int end = index + source.runs[r + 1], nextPayload = payloadPositions.nextSetBit(index);
            if (palette[id] != null && !palette[id].getBlock().hasTileEntity(palette[id]) && (nextPayload < 0 || nextPayload >= end)) {
                Arrays.fill(cells, index, end, palette[id]); index = end; continue;
            }
            for (int n = 0; n < source.runs[r + 1]; n++, index++) {
                BlockPos pos = new BlockPos(bounds.blockX(index), bounds.blockY(index), bounds.blockZ(index));
                IBlockState state = palette[id]; String raw = source.blockEntities.get(Integer.toString(index));
                TileEntity entity = null; boolean unknown = state == null;
                if (!unknown) try {
                    if (state.getBlock().hasTileEntity(state)) {
                        entity = state.getBlock().createTileEntity(level, state);
                        if (entity == null) throw new IOException("Empty entity factory");
                    }
                    if (raw != null) {
                        if (entity == null) throw new IOException("Payload on non-entity block");
                        NBTTagCompound tag = Snbt.compound(raw);
                        if (!TileEntity.getKey(entity.getClass()).toString().equals(tag.getString("id")))
                            throw new IOException("Incompatible entity type");
                        tag.setInteger("x", pos.getX()); tag.setInteger("y", pos.getY()); tag.setInteger("z", pos.getZ());
                        entity.readFromNBT(LegacyItems.decodePayload(tag.copy()));
                        if (!Handoff.preserves(tag, LegacyItems.encodePayload(entity.writeToNBT(new NBTTagCompound())))) throw new IOException("Entity payload would lose data");
                    }
                } catch (Exception incompatible) { unknown = true; }
                if (unknown) {
                    state = Epoch.UNKNOWN.getDefaultState();
                    Epoch.UnknownTile placeholder = new Epoch.UnknownTile();
                    placeholder.originalState = source.originalState(index); placeholder.originalNbt = source.originalPayload(index);
                    placeholder.sourceVersion = source.version;placeholder.sourceInstance = source.appearanceSource(index); entity = placeholder;
                }
                cells[index] = state;
                if (entity != null) { entity.setPos(pos); entities.put(pos, entity); }
            }
        }
        Map<String, Biome> biomes = EnvironmentTransfer.registry();
        String[] applied = BiomeCompatibility.project(source.biomes.expand(16384), 16, 64, 16, false, biomes.keySet());
        return new Prepared(source, cells, entities, biomes, applied);
    }

    /** Caller must hold the chunk behind the load barrier and finish lighting before release. */
    static NBTTagCompound nativeTag(NBTTagCompound previous, Prepared prepared) {
        // Supply the loader with one consistent terrain/container projection. In particular,
        // loadEntities must never consume the old native TileEntities after terrain changes.
        NBTTagCompound root = previous == null ? new NBTTagCompound() : previous.copy();
        root.setInteger("DataVersion", 1343);
        NBTTagCompound data = root.getCompoundTag("Level"); root.setTag("Level", data);
        data.setInteger("xPos", prepared.source.chunkX); data.setInteger("zPos", prepared.source.chunkZ);
        data.setBoolean("TerrainPopulated", true); data.setBoolean("LightPopulated", false);
        data.setIntArray("HeightMap", new int[256]);
        NBTTagList sections = new NBTTagList();
        for (int sectionY = 0; sectionY < 16; sectionY++) {
            ExtendedBlockStorage section = new ExtendedBlockStorage(sectionY * 16, true);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                section.set(x, y, z, prepared.cells[((sectionY * 16 + y) * 16 + z) * 16 + x]);
            byte[] blocks = new byte[4096]; NibbleArray metadata = new NibbleArray();
            NibbleArray highIds = section.getData().getDataForNBT(blocks, metadata);
            NBTTagCompound tag = new NBTTagCompound(); tag.setByte("Y", (byte)sectionY);
            tag.setByteArray("Blocks", blocks); tag.setByteArray("Data", metadata.getData());
            if (highIds != null) tag.setByteArray("Add", highIds.getData());
            tag.setByteArray("BlockLight", new byte[2048]); tag.setByteArray("SkyLight", new byte[2048]);
            sections.appendTag(tag);
        }
        data.setTag("Sections", sections);
        byte[] biomes = new byte[256];
        for (int n = 0; n < biomes.length; n++) biomes[n] = (byte)Biome.getIdForBiome(prepared.biomes.get(prepared.appliedBiomes[n]));
        data.setByteArray("Biomes", biomes);
        NBTTagList entities = new NBTTagList();
        for (TileEntity entity : prepared.entities.values()) entities.appendTag(entity.writeToNBT(new NBTTagCompound()));
        data.setTag("TileEntities", entities);
        return root;
    }

    /** Apply on the server thread, before the chunk becomes visible or starts ticking. */
    static void apply(WorldServer level, Chunk chunk, Prepared prepared) throws Exception {
        long started=System.nanoTime();
        try { applyMeasured(level, chunk, prepared); }
        finally { TerrainMetrics.record("apply",System.nanoTime()-started); }
    }

    private static void applyMeasured(WorldServer level, Chunk chunk, Prepared prepared) throws Exception {
        if (!level.getMinecraftServer().isCallingFromMinecraftThread()) throw new IOException("Terrain apply must run on the server thread");
        if (chunk.x != prepared.source.chunkX || chunk.z != prepared.source.chunkZ)
            throw new IOException("Prepared terrain belongs to a different chunk");
        for (BlockPos pos : new HashSet<>(chunk.getTileEntityMap().keySet())) if (pos.getY() >= 0 && pos.getY() < 256) {
            chunk.removeTileEntity(pos);
            // Before activation, native removal may leave the live map unchanged.
            // The load hook must also replace deferred TileEntities NBT before loadEntities runs.
            TileEntity removed = chunk.getTileEntityMap().remove(pos);
            if (removed != null) removed.invalidate();
        }
        // Direct section writes deliberately avoid placement callbacks against a half-built world.
        for (int y = 0; y < 256; y++) {
            ExtendedBlockStorage section = chunk.getBlockStorageArray()[y >> 4];
            if (section == null) { section = new ExtendedBlockStorage(y & ~15, level.provider.hasSkyLight()); chunk.getBlockStorageArray()[y >> 4] = section; }
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                section.set(x, y & 15, z, prepared.cells[(y * 16 + z) * 16 + x]);
        }
        for (TileEntity entity : prepared.entities.values()) chunk.addTileEntity(entity);
        byte[] nativeBiomes = chunk.getBiomeArray();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            nativeBiomes[z * 16 + x] = (byte) Biome.getIdForBiome(prepared.biomes.get(prepared.appliedBiomes[z * 16 + x]));
        chunk.generateSkylightMap(); chunk.setLightPopulated(false); chunk.markDirty();
    }
}
