package dev.epoch.bridge;

import java.io.IOException;
import java.util.*;

/** Canonical data waiting for native decoding has both a count and a retained-byte budget. */
public final class IncomingChunks {
    private final Map<ChunkKey, ChunkStore.Versioned> values = new HashMap<ChunkKey, ChunkStore.Versioned>();
    private long bytes;
    public synchronized void put(ChunkKey key, ChunkStore.Versioned value) throws IOException {
        if (values.containsKey(key)) return;
        long retained = value.chunk.retainedBytes();
        if (values.size() >= 128 || retained > 32L * 1024 * 1024 - bytes)
            throw new IOException("Authority decoding budget exhausted; stop chunk expansion and retry");
        values.put(key, value); bytes += retained;
    }
    public synchronized ChunkStore.Versioned remove(ChunkKey key) {
        ChunkStore.Versioned value = values.remove(key);
        if (value != null) bytes -= value.chunk.retainedBytes();
        return value;
    }
}
