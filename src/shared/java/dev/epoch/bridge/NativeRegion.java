package dev.epoch.bridge;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/** Checks whether an Anvil record is saved terrain or only an unfinished generation neighbor. */
public final class NativeRegion {
    private static final int LIMIT = 32 * 1024 * 1024;

    public static boolean hasFullChunk(Path world, ChunkKey key) throws IOException {
        Path region = world.resolve("region").resolve("r." + Math.floorDiv(key.x, 32) + "." + Math.floorDiv(key.z, 32) + ".mca");
        if (!Files.exists(region)) return false;
        byte[] bytes; int codec;
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
            long length = file.length();
            // Vanilla opens regions with CREATE even for reads. Before the first
            // chunk is saved, that file can remain empty and owns no terrain.
            if (length == 0) return false;
            if (length < 8192) throw new IOException("Truncated source region header: " + region + " (" + length + " bytes)");
            file.seek(4L * (Math.floorMod(key.x, 32) + 32 * Math.floorMod(key.z, 32)));
            int location = file.readInt(); if (location == 0) return false;
            long offset = (long)(location >>> 8) * 4096;
            if (offset < 8192 || offset + 5 > file.length()) throw new IOException("Invalid source chunk location");
            file.seek(offset); int size = file.readInt(); codec = file.readUnsignedByte();
            if ((codec & 128) != 0) {
                Path external = world.resolve("region").resolve("c." + key.x + "." + key.z + ".mcc");
                if (Files.size(external) > LIMIT) throw new IOException("Oversized external source chunk");
                bytes = Files.readAllBytes(external); codec &= 127;
            } else {
                if (size < 1 || size > LIMIT || offset + 4L + size > file.length()
                        || size + 4L > (location & 255) * 4096L) throw new IOException("Invalid source chunk size");
                bytes = new byte[size - 1]; file.readFully(bytes);
            }
        }
        InputStream input = new ByteArrayInputStream(bytes);
        if (codec == 1) input = new GZIPInputStream(input);
        else if (codec == 2) input = new InflaterInputStream(input);
        else if (codec != 3) throw new IOException("Unsupported source chunk compression: " + codec);
        try (DataInputStream data = new DataInputStream(new LimitedInput(input))) {
            if (data.readUnsignedByte() != 10) throw new IOException("Invalid source chunk NBT");
            data.readUTF(); String[] status = {null}; compound(data, 0, true, status);
            // Legacy Anvil chunks have no generation status; retain the conservative ownership rule.
            return status[0] == null || status[0].equals("full") || status[0].equals("minecraft:full");
        }
    }

    private static void compound(DataInputStream data, int depth, boolean metadata, String[] status) throws IOException {
        if (depth > 64) throw new IOException("Source chunk NBT is too deeply nested");
        for (int type; (type = data.readUnsignedByte()) != 0;) {
            String name = data.readUTF();
            if (metadata && type == 8 && name.equals("Status")) status[0] = data.readUTF();
            else if (depth == 0 && type == 10 && name.equals("Level")) compound(data, depth + 1, true, status);
            else skip(data, type, depth + 1);
        }
    }

    private static void skip(DataInputStream data, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("Source chunk NBT is too deeply nested");
        switch (type) {
            case 1: data.readByte(); return;
            case 2: data.readShort(); return;
            case 3: case 5: data.readInt(); return;
            case 4: case 6: data.readLong(); return;
            case 8: data.readUTF(); return;
            case 10: compound(data, depth, false, new String[1]); return;
            case 9: {
                int child = data.readUnsignedByte(), count = data.readInt();
                if (count < 0 || count > LIMIT || (count > 0 && (child < 1 || child > 12))) throw new IOException("Invalid NBT list");
                for (int n = 0; n < count; n++) skip(data, child, depth + 1);
                return;
            }
            case 7: case 11: case 12: {
                long count = data.readInt(); count *= type == 7 ? 1 : type == 11 ? 4 : 8;
                if (count < 0 || count > LIMIT) throw new IOException("Invalid NBT array");
                while (count > 0) { int skipped = data.skipBytes((int)count); if (skipped == 0) throw new EOFException(); count -= skipped; }
                return;
            }
            default: throw new IOException("Invalid source NBT tag: " + type);
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        int remaining = LIMIT;
        LimitedInput(InputStream input) { super(input); }
        public int read() throws IOException { if (--remaining < 0) throw new IOException("Oversized source chunk NBT"); return super.read(); }
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining == 0) throw new IOException("Oversized source chunk NBT");
            int count = in.read(buffer, offset, Math.min(length, remaining)); if (count > 0) remaining -= count; return count;
        }
        public long skip(long count) throws IOException {
            if (remaining == 0) throw new IOException("Oversized source chunk NBT");
            long skipped = in.skip(Math.min(count, remaining)); remaining -= (int)skipped; return skipped;
        }
    }
}
