package dev.epoch.bridge;

import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Installation publishes code identities; gameplay reads metadata once without hashing JARs. */
public final class ProjectionCache {
    private static final Map<Class<?>,String> CODE = new HashMap<Class<?>,String>();
    private ProjectionCache() {}

    /** Null disables native reuse when the optional code fingerprint cannot be read. */
    public static String stamp(Class<?> adapter) throws IOException {
        // Rule errors still propagate: they affect conversion correctness, not only caching.
        return stamp(adapter, CompatibilityRules.load(CompatibilityRules.directory()).fingerprint);
    }
    public static String stamp(Class<?> adapter, String rulesVersion) {
        String code = code(adapter); return code == null ? null : code + ":" + rulesVersion;
    }

    private static synchronized String code(Class<?> adapter) {
        if (CODE.containsKey(adapter)) return CODE.get(adapter);
        try {
            CodeSource source = adapter.getProtectionDomain().getCodeSource();
            if (source == null) throw new IOException("Missing adapter origin");
            URL url = source.getLocation();
            if ("jar".equalsIgnoreCase(url.getProtocol())) url = ((JarURLConnection)url.openConnection()).getJarFileURL();
            Path archive = Paths.get(url.toURI());
            if (!Files.isRegularFile(archive)) throw new IOException("Install the adapter to enable native cache reuse");
            Path mods = archive.getParent(), manifest = mods.resolve("epoch-projection.json");
            if (Files.size(manifest) > 256 * 1024) throw new IOException("Oversized installation manifest");
            com.google.gson.JsonObject installed = new com.google.gson.JsonParser().parse(
                    new String(Files.readAllBytes(manifest), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            com.google.gson.JsonObject files = installed.getAsJsonObject("files");
            Set<String> actual = new HashSet<String>();
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(mods, "*.jar")) {
                for (Path jar : jars) {
                    String name = jar.getFileName().toString(); actual.add(name);
                    com.google.gson.JsonObject entry = files.getAsJsonObject(name);
                    if (entry == null || Files.size(jar) != entry.get("size").getAsLong()
                            || Files.getLastModifiedTime(jar).toMillis() != entry.get("modified").getAsLong())
                        throw new IOException("Installation changed; reinstall Epoch to refresh native cache identity");
                }
            }
            if (actual.size() != files.entrySet().size() || !actual.contains(archive.getFileName().toString())) throw new IOException("Mod list changed");
            String result = installed.get("fingerprint").getAsString();
            if (!result.matches("[0-9a-f]{64}")) throw new IOException("Invalid installation fingerprint");
            CODE.put(adapter, result); return result;
        } catch (Exception error) {
            // Recompute from authority normally; failure of an optimization must not stop loading.
            CODE.put(adapter, null);
            System.err.println("[Epoch] Native terrain cache disabled for " + adapter.getName() + ": " + error);
            return null;
        }
    }

    public static boolean reusable(String world, String expectedWorld, long revision, long latest,
            String savedStamp, String currentStamp) {
        return expectedWorld.equals(world) && revision > 0 && revision == latest
                && currentStamp != null && currentStamp.equals(savedStamp);
    }
}
