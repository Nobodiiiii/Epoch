package dev.epoch.bridge;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class WorldSessionTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError(); }
    private static void rejects(Action action) throws Exception {
        checks++; try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Unsafe operation was accepted");
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            try (WorldSession session = WorldSession.open(Paths.get(args[0]), "child", false)) {
                System.out.println("LOCKED"); System.out.flush(); Thread.sleep(60000);
            }
            return;
        }
        Path root = Files.createTempDirectory("epoch-restart-tests-");
        try {
            Path authority = root.resolve("authority");
            WorldSession source = WorldSession.open(authority, "source", false);
            rejects(() -> WorldSession.open(authority, "other", false));
            rejects(() -> WorldSession.open(authority, "source", true));
            WorldSession target = WorldSession.open(authority, "target", true);
            source.close();
            rejects(() -> WorldSession.open(authority, "other", false));
            target.close();
            try (WorldSession restarted = WorldSession.open(authority, "other", false)) { check(true); }
            Process child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), WorldSessionTest.class.getName(), authority.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                check("LOCKED".equals(new BufferedReader(new InputStreamReader(child.getInputStream())).readLine()));
                rejects(() -> WorldSession.open(authority, "other", false));
            } finally { child.destroyForcibly(); child.waitFor(); }
            try (WorldSession restarted = WorldSession.open(authority, "other", false)) { check(true); }

            Path world = root.resolve("world"); Files.createDirectories(world);
            WorldBinding binding = WorldBinding.open(world, root.resolve("worlds"), "1.20.1", "source");
            ChunkStore store = new ChunkStore(Paths.get(binding.authority), binding.worldId);
            ChunkStore.Lease lease = store.transfer(0, "source");
            Snapshot snapshot = new Snapshot(); snapshot.version = "1.20.1";
            snapshot.palette.add("{Name:\"minecraft:air\"}"); snapshot.runs = new int[]{0, snapshot.volume()};
            snapshot.x = 123.5; snapshot.inventory = "[]";
            check(SavedWorld.latest(binding) == null);
            SavedWorld.publish(snapshot, binding, lease);
            Path first = SavedWorld.latest(binding);
            check(Snapshot.read(first).x == 123.5);
            store.transfer(lease.epoch, "target");
            rejects(() -> StreamCheckpoint.verify(first, binding, store));
            check(SavedWorld.authorize(first, binding, store).sourceInstance.equals("target"));
            // An interrupted publication leaves the previously selected checkpoint usable.
            Path incomplete = first.resolveSibling("ffffffff-ffff-ffff-ffff-ffffffffffff.json.gz");
            Files.write(incomplete, new byte[]{1,2,3});
            check(SavedWorld.latest(binding).equals(first));
            snapshot.x = 456.5; SavedWorld.publish(snapshot, binding, store.lease());
            check(Snapshot.read(SavedWorld.latest(binding)).x == 456.5);
            check(Snapshot.read(first).x == 123.5);
            Files.write(SavedWorld.latest(binding), new byte[]{1});
            rejects(() -> SavedWorld.latest(binding));
            System.out.println("WorldSessionTest: " + checks + " checks passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
