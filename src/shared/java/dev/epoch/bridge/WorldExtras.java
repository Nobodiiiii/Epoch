package dev.epoch.bridge;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Schema-2 environment payload. Native NBT remains typed SNBT, never JSON numbers. */
public final class WorldExtras {
    public Biomes biomes;
    public Weather weather;
    public Map<String, String> gameRules = new TreeMap<String, String>();
    public String player;

    public static final class Weather {
        public boolean raining, thundering;
        public int rainTime, thunderTime, clearTime;
        public float rainLevel, thunderLevel;
        public void validate() throws IOException {
            if (rainTime < 0 || thunderTime < 0 || clearTime < 0 || !Float.isFinite(rainLevel)
                || !Float.isFinite(thunderLevel) || rainLevel < 0 || rainLevel > 1 || thunderLevel < 0 || thunderLevel > 1)
                throw new IOException("Invalid weather state");
        }
    }

    /** One sample per block in X/Z and four blocks in Y; retains legacy sub-quart boundaries. */
    public static final class Biomes {
        public List<String> palette = new ArrayList<String>();
        public int[] runs;
        public String[] expand(int volume) throws IOException {
            if (palette == null || palette.isEmpty() || palette.size() > volume || runs == null
                    || runs.length > volume * 2 || (runs.length & 1) != 0) throw new IOException("Invalid biome palette/runs");
            for (String id : palette) if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IOException("Invalid biome ID");
            String[] out = new String[volume]; int position = 0;
            for (int n = 0; n < runs.length; n += 2) {
                if (runs[n] < 0 || runs[n] >= palette.size() || runs[n + 1] < 1 || runs[n + 1] > volume - position)
                    throw new IOException("Invalid biome run");
                Arrays.fill(out, position, position + runs[n + 1], palette.get(runs[n])); position += runs[n + 1];
            }
            if (position != volume) throw new IOException("Incomplete biome data");
            return out;
        }
        public static Biomes of(String[] cells) {
            Biomes out = new Biomes(); Map<String, Integer> ids = new LinkedHashMap<String, Integer>();
            List<Integer> runs = new ArrayList<Integer>(); int previous = -1, count = 0;
            for (String cell : cells) {
                Integer id = ids.get(cell);
                if (id == null) { id = ids.size(); ids.put(cell, id); out.palette.add(cell); }
                if (id == previous) count++;
                else { if (count > 0) { runs.add(previous); runs.add(count); } previous = id; count = 1; }
            }
            if (count > 0) { runs.add(previous); runs.add(count); }
            out.runs = new int[runs.size()]; for (int n = 0; n < out.runs.length; n++) out.runs[n] = runs.get(n);
            return out;
        }
    }

    public void validate(Snapshot snapshot) throws IOException {
        if (snapshot.y0 % 4 != 0 || snapshot.sizeY % 4 != 0 || biomes == null || weather == null
                || gameRules == null || gameRules.size() > 2048 || player == null || player.length() > 4 * 1024 * 1024)
            throw new IOException("Incomplete/oversized world extras");
        biomes.expand(snapshot.sizeX * snapshot.sizeZ * (snapshot.sizeY / 4)); weather.validate();
        for (Map.Entry<String, String> entry : gameRules.entrySet())
            if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_.:-]+") || entry.getValue() == null || entry.getValue().length() > 256)
                throw new IOException("Invalid game rule");
    }

    /** Per-save residuals, scoped by exact shared bounds and matched against actual native cells. */
    public static final class Carry {
        public int schema = 1;
        public int x0, y0, z0, sizeX, sizeY, sizeZ;
        public Biomes original, applied;
        public Map<String, String> rules = new TreeMap<String, String>();
        public long clearUntil;
        public String playerOriginal, playerApplied;

        public void bounds(Snapshot s) { x0=s.x0; y0=s.y0; z0=s.z0; sizeX=s.sizeX; sizeY=s.sizeY; sizeZ=s.sizeZ; }
        public boolean matches(Snapshot s) { return schema==1 && x0==s.x0 && y0==s.y0 && z0==s.z0 && sizeX==s.sizeX && sizeY==s.sizeY && sizeZ==s.sizeZ; }
        /** Split legacy region residuals when first adopting a save into the chunk authority. */
        public Carry chunk(ChunkKey key) throws IOException {
            int wx = key.x * 16, wz = key.z * 16;
            if (y0 != 0 || sizeY != 256 || wx < x0 || wz < z0 || (long)wx + 16 > (long)x0 + sizeX || (long)wz + 16 > (long)z0 + sizeZ)
                return null;
            String[] before = original.expand(sizeX * sizeZ * 64), after = applied.expand(sizeX * sizeZ * 64);
            String[] chunkBefore = new String[16384], chunkAfter = new String[16384];
            for (int y = 0; y < 64; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int local = (y * 16 + z) * 16 + x, source = (y * sizeZ + wz - z0 + z) * sizeX + wx - x0 + x;
                chunkBefore[local] = before[source]; chunkAfter[local] = after[source];
            }
            Carry out = new Carry(); out.x0 = wx; out.z0 = wz; out.sizeX = out.sizeZ = 16; out.sizeY = 256;
            out.original = Biomes.of(chunkBefore); out.applied = Biomes.of(chunkAfter); return out;
        }
        public String[] restore(Snapshot s, String[] current) throws IOException {
            if (!matches(s)) throw new IOException("Environment carry bounds mismatch");
            if (original == null || applied == null || rules == null) throw new IOException("Incomplete environment carry");
            String[] source=original.expand(current.length), expected=applied.expand(current.length), out=current.clone();
            for(int n=0;n<out.length;n++) if(current[n].equals(expected[n])) out[n]=source[n];
            return out;
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static Map<String,Object> diagnostics(Carry carry) throws IOException {
        Map<String,Object> out=new LinkedHashMap<String,Object>();
        int volume=carry.sizeX*carry.sizeZ*(carry.sizeY/4), changed=0;
        String[] original=carry.original.expand(volume),applied=carry.applied.expand(volume);
        for(int n=0;n<volume;n++)if(!original[n].equals(applied[n]))changed++;
        out.put("snapshotSchema",2);out.put("biomeCells",volume);out.put("preservedBiomeCells",changed);
        out.put("inactiveGameRules",new ArrayList<String>(carry.rules.keySet()));out.put("playerGameplayState",true);
        return out;
    }
    public static Carry readCarry(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)) {
            Carry carry = GSON.fromJson(reader, Carry.class);
            if (carry == null || carry.schema != 1) throw new IOException("Invalid environment carry");
            if(carry.sizeX<1||carry.sizeX>128||carry.sizeZ<1||carry.sizeZ>128||carry.sizeY<4||carry.sizeY>256||carry.sizeY%4!=0
                ||carry.original==null||carry.applied==null||carry.rules==null||carry.rules.size()>2048)
                throw new IOException("Incomplete environment carry");
            int volume=carry.sizeX*carry.sizeZ*(carry.sizeY/4);carry.original.expand(volume);carry.applied.expand(volume);
            for(Map.Entry<String,String> entry:carry.rules.entrySet())if(entry.getKey()==null||entry.getValue()==null||entry.getKey().length()>256||entry.getValue().length()>256)throw new IOException("Invalid carried rule");
            if((carry.playerOriginal==null)!=(carry.playerApplied==null)||carry.playerOriginal!=null&&(carry.playerOriginal.length()>4*1024*1024||carry.playerApplied.length()>4*1024*1024))throw new IOException("Invalid carried player");
            return carry;
        } catch (RuntimeException error) { throw new IOException("Malformed environment carry", error); }
    }
    public static void writeCarry(Path file, Carry carry) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "environment-", ".tmp");
        try {
            try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temp)), StandardCharsets.UTF_8)) { GSON.toJson(carry, writer); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    public static void validateRule(String key, String value, String nativeValue) throws IOException {
        if ("true".equals(nativeValue) || "false".equals(nativeValue)) {
            if (!"true".equals(value) && !"false".equals(value)) throw new IOException("Invalid boolean rule " + key);
        } else try { Integer.parseInt(value); } catch (NumberFormatException error) { throw new IOException("Invalid integer rule " + key, error); }
    }
}
