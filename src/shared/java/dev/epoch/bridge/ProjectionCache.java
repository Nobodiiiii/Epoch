package dev.epoch.bridge;

import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** A native cache is reusable only for the exact installed code and active conversion rules. */
public final class ProjectionCache {
    private static final Map<Class<?>,String> CODE = new HashMap<Class<?>,String>();
    private ProjectionCache() {}

    /** Null disables native reuse when the optional code fingerprint cannot be read. */
    public static String stamp(Class<?> adapter) throws IOException {
        String code = code(adapter);
        // Rule errors still propagate: they affect conversion correctness, not only caching.
        return code == null ? null : code + ":" + CompatibilityRules.load(CompatibilityRules.directory()).fingerprint;
    }

    private static synchronized String code(Class<?> adapter) {
        if (CODE.containsKey(adapter)) return CODE.get(adapter);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CodeSource source = adapter.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) throw new IOException("Missing adapter code source");
            URL url = source.getLocation();
            // LaunchWrapper supplies the class resource's jar:file: URL as CodeSource.
            // Resolve the physical archive without creating or relying on a zip filesystem.
            if ("jar".equalsIgnoreCase(url.getProtocol()))
                url = ((JarURLConnection) url.openConnection()).getJarFileURL();
            Path location = Paths.get(url.toURI());
            List<Path> files = new ArrayList<Path>();
            if (Files.isDirectory(location)) {
                try (java.util.stream.Stream<Path> paths = Files.walk(location)) {
                    paths.filter(Files::isRegularFile).sorted().forEach(files::add);
                }
            } else {
                files.add(location);
                // Registry changes from another installed mod invalidate native projections too.
                if (location.getParent() != null && location.getParent().getFileName() != null
                        && location.getParent().getFileName().toString().equals("mods"))
                    try (DirectoryStream<Path> jars = Files.newDirectoryStream(location.getParent(), "*.jar")) {
                        for (Path jar : jars) if (!jar.equals(location)) files.add(jar);
                    }
            }
            Collections.sort(files);
            byte[] buffer = new byte[8192];
            for (Path file : files) try (InputStream input = Files.newInputStream(file)) {
                digest.update(file.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                int n; while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            for (String resource : new String[]{"/assets/epoch/legacy-blockstates.json", "/assets/epoch/compatibility/vanilla.json"})
                try (InputStream input = adapter.getResourceAsStream(resource)) {
                    if (input != null) { int n; while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n); }
                }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            String result = hex.toString(); CODE.put(adapter, result); return result;
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
