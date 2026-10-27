package dev.epoch.bridge;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable copy-on-write metadata tree. Only changed shards are copied; no terrain is hashed. */
final class TerrainIndex {
    private static final Gson JSON = new Gson();
    private final Path directory;
    TerrainIndex(Path authority) { directory = authority.resolve("indexes"); }
    private JsonObject read(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IOException("Invalid terrain index identity");
        Path file = directory.resolve(id.substring(0, 2)).resolve(id + ".json");
        if (!Files.isRegularFile(file)) file = directory.resolve(id + ".json");
        if (Files.size(file) > 32 * 1024 * 1024) throw new IOException("Terrain index exceeds budget");
        try { return new JsonParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException bad) { throw new IOException("Invalid terrain index", bad); }
    }
    private String write(JsonObject value) throws IOException {
        String id = UUID.randomUUID().toString(); Path shard = directory.resolve(id.substring(0, 2));
        Files.createDirectories(shard); Path file = shard.resolve(id + ".json");
        Path temp = Files.createTempFile(shard, "index-", ".tmp");
        try {
            Files.write(temp, JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
        return id;
    }
    String update(String previous, Map<String, JsonElement> updates) throws IOException {
        JsonObject root = previous == null ? new JsonObject() : read(previous);
        Map<String, JsonObject> changed = new TreeMap<String, JsonObject>();
        for (Map.Entry<String, JsonElement> update : updates.entrySet()) {
            String bucket = Integer.toString(update.getKey().hashCode() & 255);
            JsonObject shard = changed.get(bucket);
            if (shard == null) {
                shard = root.has(bucket) ? read(root.get(bucket).getAsString()) : new JsonObject();
                changed.put(bucket, shard);
            }
            shard.add(update.getKey(), update.getValue());
        }
        for (Map.Entry<String, JsonObject> shard : changed.entrySet()) root.addProperty(shard.getKey(), write(shard.getValue()));
        return write(root);
    }
    Map<String, JsonElement> entries(String id) throws IOException {
        JsonObject root = read(id);
        if (root.entrySet().size() > 256) throw new IOException("Invalid terrain index root");
        Map<String, JsonElement> entries = new TreeMap<String, JsonElement>();
        for (Map.Entry<String, JsonElement> bucket : root.entrySet()) {
            if (!bucket.getKey().matches("[0-9]{1,3}") || Integer.parseInt(bucket.getKey()) > 255)
                throw new IOException("Invalid terrain index shard");
            for (Map.Entry<String, JsonElement> entry : read(bucket.getValue().getAsString()).entrySet()) {
                ChunkKey.parse(entry.getKey()); entries.put(entry.getKey(), entry.getValue());
            }
        }
        return entries;
    }
}
