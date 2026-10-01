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
    public int schema = 1;
    public String version = "";
    public int x0 = -32, y0 = 0, z0 = -32, sizeX = 64, sizeY = 256, sizeZ = 64;
    public List<String> palette = new ArrayList<String>();
    public int[] runs = new int[0];
    public Map<String, String> blockEntities = new LinkedHashMap<String, String>();
    public double x, y, z;
    public float yaw, pitch;
    public long gameTime, dayTime;
    public String inventory = "[]";
    public int selectedSlot;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public void validate() throws IOException {
        if (schema != 1) throw new IOException("Unsupported snapshot schema " + schema);
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
