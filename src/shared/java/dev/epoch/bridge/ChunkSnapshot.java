package dev.epoch.bridge;

import java.io.IOException;
import java.util.*;

/** Canonical terrain only. Player, clock and rules are never applied on chunk load. */
public final class ChunkSnapshot {
    public transient BlockPreservation preservation;
    public String originalState(int index) { if(preservation==null)preservation=new BlockPreservation(palette,runs,blockEntities);return preservation.state(index); }
    public String originalPayload(int index) { if(preservation==null)preservation=new BlockPreservation(palette,runs,blockEntities);return preservation.payload(index); }
    public static final int VOLUME = 16 * 16 * 256;
    public int schema = 3;
    public int chunkX, chunkZ;
    public String version = "";
    public String sourceInstance = "";
    public Map<String, String> appearanceSources = new TreeMap<String, String>();
    public String appearanceSource(int index) {
        String value = appearanceSources.get(Integer.toString(index));
        return value == null ? sourceInstance : value;
    }
    public List<String> palette = new ArrayList<String>();
    public int[] runs = new int[0];
    public Map<String, String> blockEntities = new TreeMap<String, String>();
    public WorldExtras.Biomes biomes;

    /** Independent mutable view of immutable authority content; never share arrays/maps with callers. */
    public ChunkSnapshot copy() {
        ChunkSnapshot out = new ChunkSnapshot();
        out.schema = schema; out.chunkX = chunkX; out.chunkZ = chunkZ;
        out.version = version; out.sourceInstance = sourceInstance;
        out.palette = new ArrayList<String>(palette); out.runs = runs.clone();
        out.blockEntities = new TreeMap<String, String>(blockEntities);
        out.appearanceSources = new TreeMap<String, String>(appearanceSources);
        out.biomes = new WorldExtras.Biomes();
        out.biomes.palette = new ArrayList<String>(biomes.palette); out.biomes.runs = biomes.runs.clone();
        return out;
    }

    /** Conservative retained-memory estimate, including per-entry objects and UTF-16 storage. */
    public long retainedBytes() {
        long bytes = 512L + 4L * (runs.length + biomes.runs.length);
        for (String value : palette) bytes += 64L + 2L * value.length();
        for (String value : biomes.palette) bytes += 64L + 2L * value.length();
        for (Map<String,String> map : Arrays.asList(blockEntities, appearanceSources))
            for (Map.Entry<String,String> entry : map.entrySet())
                bytes += 160L + 2L * (entry.getKey().length() + entry.getValue().length());
        return bytes;
    }

    public ChunkKey key() { return new ChunkKey(chunkX, chunkZ); }

    public void validate() throws IOException {
        try { key(); } catch (IllegalArgumentException bad) { throw new IOException("Invalid chunk coordinates", bad); }
        if (schema != 3 || version == null || version.isEmpty() || version.length() > 128)
            throw new IOException("Invalid chunk schema/version");
        Snapshot blocks = blocks();
        blocks.validate();
        long stateSize = 0;
        for (String state : palette) {
            if (state == null || state.isEmpty() || state.length() > 65536)
                throw new IOException("Invalid canonical block state");
            stateSize += state.length();
            if (stateSize > 8 * 1024 * 1024) throw new IOException("Chunk palette exceeds limit");
        }
        long payloadSize = 0;
        for (Map.Entry<String, String> entry : blockEntities.entrySet()) {
            if (!Integer.toString(Integer.parseInt(entry.getKey())).equals(entry.getKey()))
                throw new IOException("Noncanonical block entity index");
            String payload = entry.getValue();
            payloadSize += payload.length();
            if (payload.length() > 4 * 1024 * 1024 || payloadSize > 16 * 1024 * 1024)
                throw new IOException("Chunk block entity payload exceeds limit");
        }
        if (biomes == null) throw new IOException("Missing canonical biomes");
        biomes.expand(VOLUME / 4);
    }

    /** Adapter input; intentionally excludes global/player fields and extras. */
    public Snapshot blocks() {
        Snapshot s = new Snapshot();
        s.preservation = preservation;
        s.version = version; s.x0 = chunkX * 16; s.z0 = chunkZ * 16;
        s.sizeX = 16; s.sizeZ = 16;
        s.sourceInstance = sourceInstance; s.appearanceSources = appearanceSources;
        s.palette = palette; s.runs = runs; s.blockEntities = blockEntities;
        return s;
    }

    /** Migrate a complete old region without losing air deletions or raw NBT. */
    public static List<ChunkSnapshot> split(Snapshot source) throws IOException {
        source.validate();
        if (source.x0 % 16 != 0 || source.z0 % 16 != 0 || source.sizeX % 16 != 0
                || source.sizeZ % 16 != 0 || source.y0 != 0 || source.sizeY != 256)
            throw new IOException("Chunk migration requires aligned complete columns");
        // Schema 1 has no biome evidence; the adapter must capture it before migration.
        if (source.extras == null) throw new IOException("Capture missing biomes before migrating schema 1");
        int[] cells = new int[source.sizeX * source.sizeZ * source.sizeY];
        int cursor = 0;
        for (int n = 0; n < source.runs.length; n += 2) {
            Arrays.fill(cells, cursor, cursor + source.runs[n + 1], source.runs[n]);
            cursor += source.runs[n + 1];
        }
        String[] biomeCells = source.extras.biomes.expand(cells.length / 4);
        List<ChunkSnapshot> result = new ArrayList<ChunkSnapshot>();
        for (int oz = 0; oz < source.sizeZ; oz += 16) for (int ox = 0; ox < source.sizeX; ox += 16) {
            ChunkSnapshot chunk = new ChunkSnapshot();
            chunk.chunkX = (source.x0 + ox) / 16; chunk.chunkZ = (source.z0 + oz) / 16;
            chunk.version = source.version; chunk.sourceInstance = source.sourceInstance;
            Map<Integer, Integer> remap = new HashMap<Integer, Integer>();
            List<Integer> runs = new ArrayList<Integer>(); int previous = -1, count = 0;
            String[] biomes = new String[VOLUME / 4];
            for (int y = 0; y < 256; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int index = (y * 16 + z) * 16 + x;
                int sourceIndex = (y * source.sizeZ + oz + z) * source.sizeX + ox + x;
                Integer id = remap.get(cells[sourceIndex]);
                if (id == null) {
                    id = chunk.palette.size(); remap.put(cells[sourceIndex], id);
                    chunk.palette.add(source.palette.get(cells[sourceIndex]));
                }
                if (id == previous) count++;
                else { if (count > 0) { runs.add(previous); runs.add(count); } previous = id; count = 1; }
                String appearance = source.appearanceSources.get(Integer.toString(sourceIndex));
                if (appearance != null) chunk.appearanceSources.put(Integer.toString(index), appearance);
                String payload = source.blockEntities.get(Integer.toString(sourceIndex));
                if (payload != null) chunk.blockEntities.put(Integer.toString(index), payload);
                if (y < 64) biomes[index] = biomeCells[sourceIndex];
            }
            runs.add(previous); runs.add(count); chunk.runs = new int[runs.size()];
            for (int n = 0; n < runs.size(); n++) chunk.runs[n] = runs.get(n);
            chunk.biomes = WorldExtras.Biomes.of(biomes); chunk.validate(); result.add(chunk);
        }
        return result;
    }
}
