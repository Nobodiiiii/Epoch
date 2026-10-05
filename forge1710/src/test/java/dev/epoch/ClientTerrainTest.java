package dev.epoch;

import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.chunk.Chunk;

final class ClientTerrainTest {
    static void run() {
        ChunkProviderClient provider = new ChunkProviderClient(new UnknownTileLifecycleTest.TestWorld());
        LegacySelfTest.check(provider.chunkExists(-1,-1), "Expected legacy client chunkExists behavior");
        LegacySelfTest.check(ClientTerrain.receivedNearby(provider,-0.5,-0.5)==0,
                "Missing chunks must not count as received terrain");
        for(int z=-2;z<=0;z++) for(int x=-2;x<=0;x++) provider.loadChunk(x,z);
        LegacySelfTest.check(ClientTerrain.receivedNearby(provider,-0.5,-0.5)==0,
                "Allocated chunks without packet data were accepted");
        for(int z=-2;z<=0;z++) for(int x=-2;x<=0;x++) {
            Chunk chunk=provider.provideChunk(x,z);
            chunk.fillChunk(new byte[256],0,0,true);
        }
        LegacySelfTest.check(ClientTerrain.receivedNearby(provider,-0.5,-0.5)==9,
                "Received all-air chunks or negative coordinates were rejected");
        provider.unloadChunk(-1,-1);
        LegacySelfTest.check(ClientTerrain.receivedNearby(provider,-0.5,-0.5)==8,
                "Unloaded player chunk remained ready");
        System.out.println("PASS: client missing, allocated, received air and unloaded terrain readiness");
    }
}
