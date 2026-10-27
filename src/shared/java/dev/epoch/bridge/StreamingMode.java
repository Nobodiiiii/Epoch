package dev.epoch.bridge;

/** Available before mod initialization; transformers and adapters share this decision. */
public final class StreamingMode {
    public static final int PROTOCOL = 5;
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("epoch.streaming",
            Boolean.getBoolean("epoch.managed") ? "false" : "true"));
    private StreamingMode() {}
    public static boolean enabled() { return ENABLED; }
    public static void requireBoundWorld(java.nio.file.Path world) {
        if (!ENABLED && java.nio.file.Files.exists(world.resolve("epoch/authority.json")))
            throw new IllegalStateException("This world uses Epoch terrain authority; reopen it with streaming enabled");
    }
    public static void installed(String capability) { System.setProperty("epoch.hook." + capability, "true"); }
    public static void requireLegacy() {
        if (!ENABLED) return;
        for (String capability : new String[]{"provider", "loader", "chunk"})
            if (!Boolean.getBoolean("epoch.hook." + capability))
                throw new IllegalStateException("Streaming requires the native " + capability + " hook; restart with the matching Epoch coremod");
    }
    public static void requireLegacy(ClassLoader loader) {
        if (!ENABLED) return;
        try {
            for (String name : new String[]{"net.minecraft.world.chunk.Chunk", "net.minecraft.world.chunk.storage.AnvilChunkLoader", "net.minecraft.world.gen.ChunkProviderServer"})
                Class.forName(name, false, loader);
        } catch (ClassNotFoundException missing) { throw new IllegalStateException("Streaming native classes are unavailable", missing); }
        requireLegacy();
    }
}
