package dev.epoch.bridge;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fixed source namespace: location masks and write-once pre-mutation native versions. */
final class NativeBaseline {
    private static final Gson JSON = new Gson();
    private final Path directory;
    NativeBaseline(Path authority, String identity) throws IOException {
        if (identity == null || !identity.matches("[0-9a-f-]{36}")) throw new IOException("Missing native baseline identity");
        directory = authority.resolve("native-base").resolve(identity);
        if (!Files.isDirectory(directory)) throw new IOException("Native baseline is unavailable");
    }
    static String initialize(Path authority, Path source) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("Original source world is unavailable");
        Path parent = authority.resolve("native-base"); Files.createDirectories(parent);
        Path staging = parent.resolve("initializing"); Files.createDirectories(staging.resolve("locations"));
        // This happens before the first managed gameplay boundary, never on a polling path.
        // Retrying an interrupted initializer replaces only unpublished metadata masks.
        try (DirectoryStream<Path> old = Files.newDirectoryStream(staging.resolve("locations"), "*.mask")) {
            for (Path file : old) Files.delete(file);
        }
        Path regions = source.resolve("region");
        if (Files.isDirectory(regions)) try (DirectoryStream<Path> files = Files.newDirectoryStream(regions, "r.*.*.mca")) {
            for (Path region : files) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Native baseline initialization cancelled");
                if (!region.getFileName().toString().matches("r\\.-?[0-9]+\\.-?[0-9]+\\.mca")) throw new IOException("Invalid native region name");
                byte[] mask = new byte[128];
                try (RandomAccessFile input = new RandomAccessFile(region.toFile(), "r")) {
                    if (input.length() == 0) continue;
                    if (input.length() < 8192) throw new IOException("Truncated source region header");
                    for (int slot = 0; slot < 1024; slot++) if (input.readInt() != 0) mask[slot >>> 3] |= (byte)(1 << (slot & 7));
                }
                String name = region.getFileName().toString().replace(".mca", ".mask");
                Files.write(staging.resolve("locations").resolve(name), mask);
            }
        }
        String identity = UUID.randomUUID().toString();
        Map<String,String> origin = new LinkedHashMap<String,String>(); origin.put("id", identity); origin.put("source", source.toAbsolutePath().normalize().toString());
        Files.write(staging.resolve("origin.json"), JSON.toJson(origin).getBytes(StandardCharsets.UTF_8));
        if (Thread.currentThread().isInterrupted()) throw new IOException("Native baseline initialization cancelled");
        Files.move(staging, parent.resolve(identity), StandardCopyOption.ATOMIC_MOVE);
        return identity;
    }
    boolean covered(ChunkKey key) throws IOException {
        Path file = directory.resolve("locations").resolve("r." + Math.floorDiv(key.x, 32) + "." + Math.floorDiv(key.z, 32) + ".mask");
        if (!Files.exists(file)) return false;
        byte[] mask = Files.readAllBytes(file); if (mask.length != 128) throw new IOException("Invalid native baseline location mask");
        int slot = Math.floorMod(key.x, 32) + Math.floorMod(key.z, 32) * 32;
        return (mask[slot >>> 3] & (1 << (slot & 7))) != 0;
    }
    Path path(ChunkKey key) {
        return directory.resolve("versions").resolve(Integer.toString(Math.floorDiv(key.x, 32)))
                .resolve(Integer.toString(Math.floorDiv(key.z, 32))).resolve(key.toString() + ".json");
    }
    boolean recorded(ChunkKey key) { return Files.exists(path(key)); }
    JsonElement version(ChunkKey key) throws IOException {
        Path file = path(key); if (!Files.exists(file)) return null;
        if (Files.size(file) > 1024) throw new IOException("Invalid native baseline reference");
        try { return new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject().get("ref"); }
        catch (RuntimeException bad) { throw new IOException("Malformed native baseline reference", bad); }
    }
    Map<String,JsonElement> versions() throws IOException {
        Map<String,JsonElement> result = new TreeMap<String,JsonElement>(); Path root = directory.resolve("versions");
        if (Files.isDirectory(root)) try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>)files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                String name = file.getFileName().toString(); ChunkKey key = ChunkKey.parse(name.substring(0, name.length() - 5));
                JsonElement ref = version(key); if (ref != null && !ref.isJsonNull()) result.put(key.toString(), ref);
            }
        }
        return result;
    }
}
