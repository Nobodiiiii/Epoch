package dev.epoch.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Version-neutral block snapshot. Payload NBT is never parsed or narrowed here. */
public final class Snapshot {
    public transient BlockPreservation preservation;
    public void preserveBlocks() { if(preservation==null)preservation=new BlockPreservation(palette,runs,blockEntities); }
    public String originalState(int index) { preserveBlocks();return preservation.state(index); }
    public String originalPayload(int index) { preserveBlocks();return preservation.payload(index); }
    public int schema = 1;
    public String version = "";
    public String sourceInstance = "";
    public Map<String, String> appearanceSources = new TreeMap<String, String>();
    public String appearanceSource(int index) {
        String value = appearanceSources.get(Integer.toString(index));
        return value == null ? sourceInstance : value;
    }
    public int x0 = -32, y0 = 0, z0 = -32, sizeX = 64, sizeY = 256, sizeZ = 64;
    public List<String> palette = new ArrayList<String>();
    public int[] runs = new int[0];
    public Map<String, String> blockEntities = new LinkedHashMap<String, String>();
    public double x, y, z;
    public float yaw, pitch;
    public long gameTime, dayTime;
    public String inventory = "[]";
    public int selectedSlot;
    public WorldExtras extras;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Bounds accepted by native terrain adapters; origin is independent of the player. */
    public void validateColumns() throws IOException {
        validate();
        if (y0 != 0 || sizeY != 256 || x0 % 16 != 0 || z0 % 16 != 0 || sizeX % 16 != 0 || sizeZ % 16 != 0
                || x0 < -30000000 || z0 < -30000000 || (long)x0 + sizeX > 30000000 || (long)z0 + sizeZ > 30000000)
            throw new IOException("Terrain transfer requires aligned complete columns within world bounds");
    }

    public int blockX(int index) { return x0 + index % sizeX; }
    public int blockY(int index) { return y0 + index / (sizeX * sizeZ); }
    public int blockZ(int index) { return z0 + (index / sizeX) % sizeZ; }
    public int volume() { return sizeX * sizeY * sizeZ; }
    public int minChunkX() { return Math.floorDiv(x0, 16); }
    public int minChunkZ() { return Math.floorDiv(z0, 16); }
    public int maxChunkX() { return Math.floorDiv(x0 + sizeX - 1, 16); }
    public int maxChunkZ() { return Math.floorDiv(z0 + sizeZ - 1, 16); }

    public void validate() throws IOException {
        if (schema != 1 && schema != 2) throw new IOException("Unsupported snapshot schema " + schema);
        if ((schema == 2) != (extras != null)) throw new IOException("Snapshot extras/schema mismatch");
        if (sizeX < 1 || sizeZ < 1 || sizeY < 1 || sizeX > 128 || sizeZ > 128 || sizeY > 256 || y0 < 0 || y0 + sizeY > 256)
            throw new IOException("Snapshot exceeds shared v0.1 world bounds");
        long total = (long) sizeX * sizeY * sizeZ, count = 0;
        if (palette == null || palette.isEmpty() || palette.size() > total || runs == null || (runs.length & 1) != 0 || runs.length > total * 2)
            throw new IOException("Invalid palette or run list");
        for (int i = 0; i < runs.length; i += 2) {
            if (runs[i] < 0 || runs[i] >= palette.size() || runs[i + 1] <= 0) throw new IOException("Invalid block run");
            count += runs[i + 1];
            if (count > total) throw new IOException("Run exceeds snapshot volume");
        }
        if (count != total) throw new IOException("Incomplete block data");
        if (blockEntities == null) throw new IOException("Missing block entity data");
        for (Map.Entry<String, String> e : blockEntities.entrySet()) {
            try { long index = Long.parseLong(e.getKey()); if (index < 0 || index >= total || e.getValue() == null) throw new NumberFormatException(); }
            catch (NumberFormatException bad) { throw new IOException("Invalid block entity index", bad); }
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IOException("Non-finite player camera");
        if (selectedSlot < 0 || selectedSlot > 8) throw new IOException("Invalid selected slot");
        if (sourceInstance == null || (!sourceInstance.isEmpty() && !sourceInstance.matches("[A-Za-z0-9_.-]{1,128}")) || appearanceSources == null || appearanceSources.size() > total)
            throw new IOException("Invalid appearance provenance");
        for (Map.Entry<String, String> entry : appearanceSources.entrySet()) {
            try { int index = Integer.parseInt(entry.getKey());
                if (index < 0 || index >= total || entry.getValue() == null || (!entry.getValue().isEmpty() && !entry.getValue().matches("[A-Za-z0-9_.-]{1,128}"))) throw new IllegalArgumentException();
            } catch (RuntimeException bad) { throw new IOException("Invalid appearance source", bad); }
        }
        if (extras != null) extras.validate(this);
    }

    public static Snapshot read(Path path) throws IOException {
        try (InputStream input = new GZIPInputStream(Files.newInputStream(path)); Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            Snapshot snapshot = GSON.fromJson(reader, Snapshot.class);
            if (snapshot == null) throw new IOException("Empty snapshot");
            snapshot.validate();
            return snapshot;
        } catch (RuntimeException bad) { throw new IOException("Malformed snapshot", bad); }
    }

    public void write(Path path) throws IOException {
        validate();
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), "snapshot-", ".tmp");
        try {
            try (OutputStream stream = new GZIPOutputStream(Files.newOutputStream(temp)); Writer writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            try { Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
