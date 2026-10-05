package dev.epoch.bridge;

import java.io.IOException;

/** Overworld chunk identity; world UUID is supplied by the containing store. */
public final class ChunkKey {
    public static final int LIMIT = 1875000;
    public final int x, z;

    public ChunkKey(int x, int z) {
        if (x < -LIMIT || x >= LIMIT || z < -LIMIT || z >= LIMIT)
            throw new IllegalArgumentException("Chunk outside Minecraft horizontal bounds");
        this.x = x; this.z = z;
    }

    public static ChunkKey at(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || x < -30000000 || x >= 30000000 || z < -30000000 || z >= 30000000)
            throw new IllegalArgumentException("Invalid world position");
        return new ChunkKey((int) Math.floor(x / 16), (int) Math.floor(z / 16));
    }

    public static ChunkKey parse(String value) throws IOException {
        try {
            String[] parts = value.split(",", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            ChunkKey key = new ChunkKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            if (!key.toString().equals(value)) throw new IllegalArgumentException();
            return key;
        } catch (RuntimeException bad) { throw new IOException("Invalid chunk key: " + value, bad); }
    }

    @Override public String toString() { return x + "," + z; }
    @Override public boolean equals(Object other) {
        return other instanceof ChunkKey && ((ChunkKey) other).x == x && ((ChunkKey) other).z == z;
    }
    @Override public int hashCode() { return 31 * x + z; }
}
