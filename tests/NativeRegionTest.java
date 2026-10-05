package dev.epoch.bridge;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

public final class NativeRegionTest {
    private static void region(Path root, String status, boolean level) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(raw)) {
            data.writeByte(10); data.writeUTF("");
            if (level) { data.writeByte(10); data.writeUTF("Level"); }
            if (status != null) { data.writeByte(8); data.writeUTF("Status"); data.writeUTF(status); }
            if (level) data.writeByte(0);
            data.writeByte(0);
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(compressed)) { output.write(raw.toByteArray()); }
        Files.createDirectories(root.resolve("region"));
        try (RandomAccessFile file = new RandomAccessFile(root.resolve("region/r.-1.0.mca").toFile(), "rw")) {
            file.setLength(12288); file.seek(4 * 31); file.writeInt((2 << 8) | 1);
            file.seek(8192); file.writeInt(compressed.size() + 1); file.writeByte(2); file.write(compressed.toByteArray());
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("epoch-native-region-tests-"); ChunkKey key = new ChunkKey(-1, 0);
        try {
            if (NativeRegion.hasFullChunk(root, key)) throw new AssertionError();
            Path region = root.resolve("region/r.-1.0.mca");
            if (Files.exists(region)) throw new AssertionError("Absent-region probe created a file");
            Files.createDirectories(region.getParent());
            Files.createFile(region);
            if (NativeRegion.hasFullChunk(root, key)) throw new AssertionError("Empty region contains terrain");
            if (Files.size(region) != 0) throw new AssertionError("Empty-region probe changed the file");
            Files.write(region, new byte[8192]);
            if (NativeRegion.hasFullChunk(root, key)) throw new AssertionError("Empty location table contains terrain");
            for (boolean level : new boolean[]{false, true}) {
                for (String full : new String[]{null, "full", "minecraft:full"}) {
                    region(root, full, level); if (!NativeRegion.hasFullChunk(root, key)) throw new AssertionError(full);
                }
                for (String proto : new String[]{"empty", "minecraft:initialize_light", "features", "light"}) {
                    region(root, proto, level); if (NativeRegion.hasFullChunk(root, key)) throw new AssertionError(proto);
                }
            }
            for (int size : new int[]{1, 7, 4096, 8191}) {
                Files.write(region, new byte[size]);
                try { NativeRegion.hasFullChunk(root, key); throw new AssertionError("Accepted partial header: " + size); }
                catch (IOException expected) { }
                if (Files.size(region) != size) throw new AssertionError("Damaged-region probe changed the file");
            }
            // A probe must not cache an empty result after the source saves a real chunk.
            Files.write(region, new byte[0]);
            if (NativeRegion.hasFullChunk(root, key)) throw new AssertionError();
            region(root, "minecraft:full", false);
            if (!NativeRegion.hasFullChunk(root, key)) throw new AssertionError("Newly saved terrain was missed");
            if (NativeRegion.hasFullChunk(root, new ChunkKey(-2, 0))) throw new AssertionError("Absent record contains terrain");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) {
                file.setLength(8192);
            }
            try { NativeRegion.hasFullChunk(root, key); throw new AssertionError("Accepted truncated chunk data"); }
            catch (IOException expected) { }
            System.out.println("NativeRegionTest: empty/partial regions, full/proto chunks, live updates and truncated records passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
