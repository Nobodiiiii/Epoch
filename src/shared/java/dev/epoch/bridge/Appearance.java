package dev.epoch.bridge;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Render-only interchange. Never used to reconstruct gameplay state or native NBT. */
public final class Appearance {
    public static final class Mesh {
        public List<Face> faces = new ArrayList<Face>();
        public boolean occludes;
    }
    public static final class Face {
        // Four vertices: x, y, z, u, v. UVs are relative to the exported sprite.
        public float[] vertices = new float[20];
        public String texture;
        public int color = 0xffffff;
        public float nx, ny, nz;
        private transient int boundary = -2;
    }
    public interface Baker { Mesh bake(String state) throws Exception; }
    private static final Gson GSON = new Gson();
    private static final Set<String> requested = ConcurrentHashMap.newKeySet();
    private static final Queue<String> pending = new ConcurrentLinkedQueue<String>();
    private static final java.util.regex.Pattern INSTANCE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Map<String, SourceCache> renderCache = new ConcurrentHashMap<String, SourceCache>();
    private static final class SourceCache {
        final Path directory;
        final Map<String, RenderEntry> states = new ConcurrentHashMap<String, RenderEntry>();
        SourceCache(Path directory) { this.directory = directory; }
    }
    private static final class RenderEntry {
        final Path file;
        volatile Mesh mesh;
        volatile boolean offline;
        volatile long nextCheck;
        RenderEntry(Path file) { this.file = file; }
    }
    private static final Map<String, Long> attempts = new ConcurrentHashMap<String, Long>();
    private static final Map<String, byte[]> textures = new ConcurrentHashMap<String, byte[]>();
    private static final Map<String, String> hashes = new ConcurrentHashMap<String, String>();
    private static final Map<String, String> legacySources = new ConcurrentHashMap<String, String>();
    private static volatile long polled;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Epoch appearances"); t.setDaemon(true); return t;
    });
    private Appearance() {}
    public static String instance() { return System.getProperty("epoch.instanceId", ""); }
    /** Old placeholders have only a version: use it only when the catalog has a unique match. */
    public static String source(String instance, String version) {
        if (instance != null && !instance.isEmpty()) return instance;
        if (version == null || version.isEmpty()) return "";
        String known = legacySources.get(version); if (known != null) return known;
        if (legacySources.putIfAbsent(version, "") == null)
            IO.execute(() -> legacySources.put(version, SourceAppearance.uniqueInstance(version)));
        return "";
    }
    public static String key(String state) {
        String known = hashes.get(state); if (known != null) return known;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format("%02x", b & 255));
            String result = out.toString(); if (hashes.size() < 16384) hashes.put(state, result); return result;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static Path directory(String instance) {
        String root = System.getProperty("epoch.projectRoot");
        if (root == null || instance == null || !INSTANCE_ID.matcher(instance).matches()) return null;
        return Paths.get(root, ".minecraft", "epoch", "appearances", instance);
    }
    public static void request(Collection<String> states) {
        if (directory(instance()) == null) return;
        for (String state : states) if (requested.size() < 16384 && requested.add(state)) pending.add(state);
    }
    /** Called on the client render thread, including while the source simulation is frozen. */
    public static void tick(Baker baker) {
        Path own = directory(instance()); long now = System.currentTimeMillis();
        if (own != null && now - polled > 1000) {
            polled = now;
            IO.execute(() -> {
                Path inbox = own.resolve("requests");
                if (!Files.isDirectory(inbox)) return;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(inbox, "*.request")) {
                    int count = 0;
                    for (Path file : files) {
                        if (++count > 64) break;
                        if (Files.size(file) <= 65536) {
                            String state = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                            if (requested.size() < 16384 && requested.add(state)) pending.add(state);
                        }
                        Files.deleteIfExists(file);
                    }
                } catch (IOException error) { System.err.println("Epoch appearance requests: " + error); }
            });
        }
        for (int n = 0; n < 4; n++) {
            String state = pending.poll(); if (state == null) break;
            try {
                Mesh mesh = baker.bake(state);
                if (mesh == null || mesh.faces.isEmpty() || mesh.faces.size() > 4096) continue;
                Path dir = directory(instance()); if (dir == null) continue;
                String id = key(state);
                IO.execute(() -> {
                    try { write(dir.resolve(id + ".json"), GSON.toJson(mesh).getBytes(StandardCharsets.UTF_8)); }
                    catch (IOException error) { System.err.println("Epoch appearance: " + error); }
                });
            } catch (Exception error) { System.err.println("Epoch cannot bake appearance: " + error); }
        }
    }
    /** Resource bytes come from the source client's active resource manager, never the destination registry. */
    public static String texture(byte[] png) {
        return texture(instance(), png);
    }
    public static String texture(String instance, byte[] png) {
        String id;
        try {
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(png)) hash.append(String.format("%02x", b & 255));
            id = hash.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
        Path dir = directory(instance);
        if (dir != null) IO.execute(() -> {
            try { Path file = dir.resolve(id + ".png"); if (!Files.exists(file)) write(file, png); }
            catch (IOException error) { System.err.println("Epoch appearance texture: " + error); }
        });
        return id;
    }
    public static byte[] bytes(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (out.size() + n > 16 * 1024 * 1024) throw new IOException("Oversized appearance resource");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
    public static Path textureFile(String instance, String texture) {
        Path dir = directory(instance);
        return dir == null || texture == null || !texture.matches("[a-f0-9]{64}") ? null : dir.resolve(texture + ".png");
    }
    public static byte[] image(String instance, String texture) {
        String id = instance + "/" + texture; byte[] data = textures.get(id); if (data != null) return data;
        Path file = textureFile(instance, texture); if (file == null) return null;
        Long previous = attempts.get(id); long now = System.currentTimeMillis();
        if (previous == null || now - previous > 3000) {
            attempts.put(id, now);
            IO.execute(() -> { try {
                if (Files.isRegularFile(file) && Files.size(file) <= 16 * 1024 * 1024 && textures.size() < 2048)
                    textures.put(id, Files.readAllBytes(file));
            } catch (IOException ignored) {} });
        }
        return null;
    }
    public static void reload() {
        requested.clear(); pending.clear(); renderCache.clear(); attempts.clear(); textures.clear(); hashes.clear(); legacySources.clear();
    }
    /** Crop the first frame of a conventional vertically animated sprite. */
    public static byte[] firstFrame(byte[] png) throws IOException {
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        if (image == null || image.getWidth() > 4096 || image.getHeight() > 16384) throw new IOException("Invalid source texture");
        if (image.getHeight() <= image.getWidth()) return png;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image.getSubimage(0, 0, image.getWidth(), image.getWidth()), "png", out);
        return out.toByteArray();
    }
    /** No disk access on the render path; missing exports are retried as the source finishes baking. */
    public static Mesh get(String instance, String state) {
        if (instance == null || instance.isEmpty() || state == null || state.isEmpty()) return null;
        SourceCache source = renderCache.get(instance);
        if (source == null) {
            Path directory = directory(instance); if (directory == null || renderCache.size() >= 128) return null;
            SourceCache created = new SourceCache(directory);
            SourceCache existing = renderCache.putIfAbsent(instance, created); source = existing == null ? created : existing;
        }
        RenderEntry entry = source.states.get(state);
        if (entry == null) {
            if (source.states.size() >= 4096) return null;
            RenderEntry created = new RenderEntry(source.directory.resolve(key(state) + ".json"));
            RenderEntry existing = source.states.putIfAbsent(state, created); entry = existing == null ? created : existing;
        }
        // The normal render path is two map lookups and a volatile read: no hashing,
        // regex compilation, filesystem paths, futures or temporary compound keys.
        Mesh mesh = entry.mesh;
        if (mesh != null && !entry.offline) return mesh;
        if (System.currentTimeMillis() >= entry.nextCheck) {
            synchronized (entry) {
                if (System.currentTimeMillis() >= entry.nextCheck) {
                    entry.nextCheck = Long.MAX_VALUE;
                    final RenderEntry pendingEntry = entry;
                    IO.execute(() -> load(instance, state, pendingEntry));
                }
            }
        }
        return mesh;
    }
    private static void load(String instance, String state, RenderEntry entry) {
        try {
            Path file = entry.file;
            if (!Files.isRegularFile(file)) {
                if (state.length() <= 65536)
                    write(file.getParent().resolve("requests").resolve(key(state) + ".request"), state.getBytes(StandardCharsets.UTF_8));
                if (entry.mesh == null) {
                    Mesh offline = SourceAppearance.load(instance, state);
                    if (offline != null) { validate(offline); entry.offline = true; entry.mesh = offline; }
                }
                return;
            }
            if (Files.size(file) > 2 * 1024 * 1024) return;
            Mesh result = GSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Mesh.class);
            validate(result); entry.mesh = result; entry.offline = false;
        } catch (Exception ignored) { /* retain fallback/previous model while source is unavailable */ }
        finally { entry.nextCheck = System.currentTimeMillis() + 3000; }
    }
    public static void validate(Mesh mesh) throws IOException {
        if (mesh == null || mesh.faces == null || mesh.faces.isEmpty() || mesh.faces.size() > 4096) throw new IOException("Invalid mesh");
        for (Face f : mesh.faces) {
            if (f == null || f.vertices == null || f.vertices.length != 20 || f.texture == null || !f.texture.matches("[a-f0-9]{64}")) throw new IOException("Invalid face");
            for (float v : f.vertices) if (!Float.isFinite(v) || Math.abs(v) > 64) throw new IOException("Invalid vertex");
            if (!Float.isFinite(f.nx) || !Float.isFinite(f.ny) || !Float.isFinite(f.nz)) throw new IOException("Invalid normal");
        }
    }
    /** Native direction order: down, up, north, south, west, east. */
    public static int boundary(Face face) {
        if (face.boundary != -2) return face.boundary;
        for (int side=0;side<6;side++) {
            int axis=side<2?1:side<4?2:0; float plane=(side&1)==0?0:1;
            boolean on=true; for(int i=0;i<4;i++)if(Math.abs(face.vertices[i*5+axis]-plane)>.0001f)on=false;
            if(on)return face.boundary = side;
        }return face.boundary = -1;
    }
    private static void write(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent()); Path tmp = Files.createTempFile(file.getParent(), "appearance-", ".tmp");
        try {
            Files.write(tmp, data);
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    /** Scanline modulation is evaluated per strip, independently of world tick/freeze state. */
    public static float glitch(double seconds, double strip) {
        double moving = strip * 53 - seconds * 13;
        double scan = moving - Math.floor(moving);
        double burst = Math.sin(Math.floor(seconds * 9) * 12.9898 + Math.floor(strip * 32) * 78.233);
        return scan < .14 || burst > .97 ? .6f : .0f;
    }
}
