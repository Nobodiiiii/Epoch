package dev.epoch.bridge;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded local requests to the original version; responses retain canonical NBT verbatim. */
public final class SourceChunkRequests {
    public static final class Request {
        public String id, worldId;
        public int chunkX, chunkZ;
        public ChunkKey key() { return new ChunkKey(chunkX, chunkZ); }
    }
    private static final Gson JSON = new Gson();
    private SourceChunkRequests() { }

    public static ChunkSnapshot fetch(Path directory, String worldId, ChunkKey key, long timeoutMillis) throws IOException {
        Request request = new Request(); request.id = UUID.randomUUID().toString(); request.worldId = worldId;
        request.chunkX = key.x; request.chunkZ = key.z; Files.createDirectories(directory);
        Path query = directory.resolve(request.id + ".json"), response = directory.resolve(request.id + ".chunk.gz"), error = directory.resolve(request.id + ".error");
        atomic(query, JSON.toJson(request)); long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (System.nanoTime() < deadline) {
                if (Files.exists(error)) throw new IOException("Source chunk export failed: " + new String(Files.readAllBytes(error), StandardCharsets.UTF_8));
                if (Files.exists(response)) {
                    ChunkSnapshot chunk = ChunkStore.readChunk(response);
                    if (!chunk.key().equals(key)) throw new IOException("Source replied with a different chunk");
                    return chunk;
                }
                try { Thread.sleep(10); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IOException("Interrupted source request", interrupted);
                }
            }
            throw new IOException("Original version did not export chunk before timeout: " + key);
        } finally { Files.deleteIfExists(query); Files.deleteIfExists(response); Files.deleteIfExists(error); }
    }

    public static List<Request> poll(Path directory, String worldId) throws IOException {
        List<Request> requests = new ArrayList<Request>();
        if (!Files.isDirectory(directory)) return requests;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                if (Files.size(file) > 4096) throw new IOException("Oversized source request");
                try {
                    Request request = JSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Request.class);
                    if (request == null || !UUID.fromString(request.id).toString().equals(request.id)
                            || !file.getFileName().toString().equals(request.id + ".json") || !worldId.equals(request.worldId))
                        throw new IOException("Invalid source request identity");
                    request.key(); requests.add(request);
                } catch (RuntimeException malformed) { throw new IOException("Invalid source request", malformed); }
                if (requests.size() == 2) break;
            }
        }
        return requests;
    }

    public static void respond(Path directory, Request request, ChunkSnapshot chunk) throws IOException {
        if (!chunk.key().equals(request.key())) throw new IOException("Wrong source response coordinates");
        ChunkStore.writeChunk(directory.resolve(request.id + ".chunk.gz"), chunk);
        Files.deleteIfExists(directory.resolve(request.id + ".json"));
    }
    public static void reject(Path directory, Request request, Exception error) throws IOException {
        atomic(directory.resolve(request.id + ".error"), error.toString());
        Files.deleteIfExists(directory.resolve(request.id + ".json"));
    }
    private static void atomic(Path file, String text) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), "source-", ".tmp");
        try {
            Files.write(temp, text.getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
