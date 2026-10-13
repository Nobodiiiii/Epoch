import java.net.URL;
import java.nio.file.Paths;
import net.minecraft.launchwrapper.LaunchClassLoader;

/** Runs packaged adapter code through the actual legacy loader, without starting Minecraft. */
public final class LaunchWrapperCacheTest {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Expected Gson JAR followed by adapter JARs");
        for (int i = 1; i < args.length; i++) {
            URL adapter = Paths.get(args[i]).toUri().toURL(), gson = Paths.get(args[0]).toUri().toURL();
            try (LaunchClassLoader loader = new LaunchClassLoader(new URL[]{adapter, gson})) {
                Class<?> marker = loader.findClass("dev.epoch.bridge.ChunkKey");
                String origin = marker.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
                if (!origin.startsWith("jar:file:")) throw new AssertionError("Did not exercise the legacy packaged origin: " + origin);
                Class<?> cache = loader.findClass("dev.epoch.bridge.ProjectionCache");
                String stamp = (String) cache.getMethod("stamp", Class.class).invoke(null, marker);
                if (stamp == null || !stamp.matches("[0-9a-f]{64}:[0-9a-f]{64}"))
                    throw new AssertionError("Packaged adapter disabled native cache: " + args[i]);
                String again = (String) cache.getMethod("stamp", Class.class).invoke(null, marker);
                if (!stamp.equals(again)) throw new AssertionError("Repeated packaged fingerprint changed");
                System.out.println("PASS: actual LaunchWrapper packaged fingerprint: " + args[i]);
            }
        }
    }
}
