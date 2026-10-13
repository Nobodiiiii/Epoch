package dev.epoch.bridge;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** Uses packaged code origins, including the jar:file URLs returned by LaunchWrapper. */
public final class ProjectionCacheTest {
    public static final class Fixture {}
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }

    private static byte[] bytes() throws IOException {
        try (InputStream input = ProjectionCacheTest.class.getResourceAsStream("ProjectionCacheTest$Fixture.class")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static Class<?> fixture(URL origin) throws Exception {
        final byte[] bytes = bytes();
        return new SecureClassLoader(ProjectionCacheTest.class.getClassLoader()) {
            Class<?> loadFixture() {
                return defineClass(Fixture.class.getName(), bytes, 0, bytes.length,
                        origin == null ? null : new CodeSource(origin, (java.security.cert.Certificate[]) null));
            }
        }.loadFixture();
    }

    private static void jar(Path path, String extra) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry("dev/epoch/bridge/ProjectionCacheTest$Fixture.class"));
            out.write(bytes()); out.closeEntry();
            out.putNextEntry(new JarEntry("unloaded-resource.txt"));
            out.write(extra.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("epoch fingerprint # + 空间-");
        String oldRules = System.getProperty("epoch.compatibilityDir");
        try {
            Path mods = Files.createDirectory(root.resolve("mods"));
            Path adapter = mods.resolve("epoch managed.jar"); jar(adapter, "first");
            System.setProperty("epoch.compatibilityDir", Files.createDirectory(root.resolve("rules")).toString());
            URL file = adapter.toUri().toURL();
            URL wrapped = new URL("jar:" + file.toExternalForm() + "!/dev/epoch/bridge/ProjectionCacheTest$Fixture.class");
            // The original implementation fails here without an already mounted zip filesystem.
            String fromJar = ProjectionCache.stamp(fixture(wrapped));
            String fromFile = ProjectionCache.stamp(fixture(file));
            check(fromJar != null && fromJar.equals(fromFile), "Packaged JAR and file origins must fingerprint the same content");
            check(fromJar.equals(ProjectionCache.stamp(fixture(new URL("jar:" + file + "!/")))), "JAR-root origin differs");
            Path other = mods.resolve("other-mod.jar"); jar(other, "other");
            String withMod = ProjectionCache.stamp(fixture(wrapped));
            check(!fromJar.equals(withMod), "Adding a neighboring mod did not invalidate native cache");
            jar(other, "changed");
            check(!withMod.equals(ProjectionCache.stamp(fixture(wrapped))), "Changing another mod did not invalidate native cache");
            Files.delete(other); jar(adapter, "changed adapter resource");
            check(!fromJar.equals(ProjectionCache.stamp(fixture(wrapped))), "Changing unused adapter content did not invalidate native cache");
            Path directory = Files.createDirectory(root.resolve("classes")); Files.write(directory.resolve("test.bin"), new byte[]{1});
            check(ProjectionCache.stamp(fixture(directory.toUri().toURL())) != null, "Development directory origin failed");
            Class<?> unavailable = fixture(root.resolve("missing.jar").toUri().toURL());
            check(ProjectionCache.stamp(unavailable) == null && ProjectionCache.stamp(unavailable) == null, "Unavailable fingerprint must disable reuse without blocking terrain");
            check(ProjectionCache.stamp(fixture(null)) == null, "Missing code source must disable reuse");
            check(!ProjectionCache.reusable("world", "world", 1, 1, null, null), "Unavailable fingerprint accepted as a reusable cache");
            Files.write(root.resolve("rules/invalid.json"), "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            boolean rejected = false;
            try { ProjectionCache.stamp(fixture(file)); } catch (IOException expected) { rejected = true; }
            check(rejected, "Invalid conversion rules were swallowed as a cache failure");
            System.out.println("ProjectionCacheTest: " + checks + " checks passed (JAR/file/directory origins, invalidation, safe fallback)");
        } finally {
            if (oldRules == null) System.clearProperty("epoch.compatibilityDir"); else System.setProperty("epoch.compatibilityDir", oldRules);
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
