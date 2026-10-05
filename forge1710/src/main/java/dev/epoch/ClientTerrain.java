package dev.epoch;

import dev.epoch.bridge.ChunkKey;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.IChunkProvider;

/** Checks received terrain; the 1.7 client provider's chunkExists always returns true. */
final class ClientTerrain {
    static int receivedNearby(IChunkProvider provider, double x, double z) {
        ChunkKey center = ChunkKey.at(x, z);
        int received = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            Chunk chunk = provider.provideChunk(center.x + dx, center.z + dz);
            // fillChunk sets both flags only after the packet's terrain has been applied.
            // A real all-air chunk is valid; the provider's missing-chunk sentinel is not.
            if (chunk != null && !(chunk instanceof EmptyChunk)
                    && chunk.isChunkLoaded && chunk.isTerrainPopulated && chunk.isLightPopulated) received++;
        }
        return received;
    }
}
