package dev.epoch.bridge;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Stable world identity, separate from a launcher profile or a single game session. */
public final class WorldBinding {
    public int schema = 3;
    public String worldId, authority, sourceWorld, sourceVersion, sourceInstance;
    private static final Gson JSON = new Gson();

    public static WorldBinding open(Path world, Path authorities, String version, String instance) throws IOException {
        Path file = world.resolve("epoch/authority.json");
        if (Files.exists(file)) return read(file);
        WorldBinding binding = new WorldBinding(); binding.worldId = UUID.randomUUID().toString();
        binding.authority = authorities.toAbsolutePath().normalize().resolve(binding.worldId).toString();
        binding.sourceWorld = world.toRealPath().toString(); binding.sourceVersion = version; binding.sourceInstance = instance;
        binding.write(file); return binding;
    }

    public static WorldBinding read(Path file) throws IOException {
        if (Files.size(file) > 16384) throw new IOException("Oversized world binding");
        try {
            WorldBinding binding = JSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), WorldBinding.class);
            if (binding == null) throw new IOException("Empty world binding");
            binding.validate(); return binding;
        } catch (RuntimeException bad) { throw new IOException("Malformed world binding", bad); }
    }

    public void write(Path file) throws IOException {
        validate(); Path absolute = file.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temp = Files.createTempFile(absolute.getParent(), "binding-", ".tmp");
        try {
            Files.write(temp, JSON.toJson(this).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private void validate() throws IOException {
        try {
            if (schema != 3 || !UUID.fromString(worldId).toString().equals(worldId)
                    || !Paths.get(authority).isAbsolute() || !Paths.get(sourceWorld).isAbsolute()
                    || sourceVersion == null || sourceVersion.isEmpty() || sourceInstance == null
                    || !sourceInstance.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException();
        } catch (RuntimeException bad) { throw new IOException("Invalid world binding", bad); }
    }
}
