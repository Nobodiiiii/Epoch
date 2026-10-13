package dev.epoch;

import dev.epoch.bridge.PackedBlocks;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.*;
import net.minecraft.world.storage.ThreadedFileIOBase;

/** Exercises actual Anvil hooks with streaming disabled, without launching the client. */
public final class CompactNativeSaveTest {
    private static void run() throws Exception {
        if (Boolean.getBoolean("epoch.streaming")) throw new AssertionError("Test requires streaming disabled");
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),Collections.emptyList());
        net.minecraft.init.Bootstrap.func_151354_b();
        Block.blockRegistry.addObject(3000, "epoch:packed_unknown", CompactUnknowns.BLOCK);
        // Supply the event bus registration owner normally set by Forge initialization.
        cpw.mods.fml.common.LoadController controller = new cpw.mods.fml.common.LoadController(cpw.mods.fml.common.Loader.instance());
        java.lang.reflect.Field active = controller.getClass().getDeclaredField("activeContainer"); active.setAccessible(true);
        cpw.mods.fml.common.ModMetadata metadata = new cpw.mods.fml.common.ModMetadata(); metadata.modId = "epoch"; metadata.name = "Epoch test";
        active.set(controller, new cpw.mods.fml.common.DummyModContainer(metadata));
        java.lang.reflect.Field controllers = cpw.mods.fml.common.Loader.class.getDeclaredField("modController"); controllers.setAccessible(true);
        controllers.set(cpw.mods.fml.common.Loader.instance(), controller);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new StreamingWorld());
        NativeSaveQueueTest.TestWorld world = new NativeSaveQueueTest.TestWorld();
        Chunk chunk = new Chunk(world, -2, 3);
        PackedBlocks.Entry identity = new PackedBlocks.Entry("{Name:\"minecraft:granite\"}", "1.20.1", "native-roundtrip-source");
        Map<Integer, PackedBlocks.Entry> entries = new TreeMap<>();
        for (int sy = 0; sy < 16; sy++) {
            ExtendedBlockStorage section = new ExtendedBlockStorage(sy * 16, true);
            chunk.getBlockStorageArray()[sy] = section;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                section.func_150818_a(x, y, z, CompactUnknowns.BLOCK);
                entries.put((((sy * 16 + y) * 16) + z) * 16 + x, identity);
            }
        }
        CompactUnknowns.install(chunk, new PackedBlocks(entries));
        java.io.File directory = Files.createTempDirectory("epoch-compact-native-").toFile();
        AnvilChunkLoader loader = new AnvilChunkLoader(directory);
        loader.saveChunk(world, chunk);
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        CompactUnknowns.clear();
        Chunk restored = loader.loadChunk(world, -2, 3);
        if (restored == null || restored.getBlock(15, 255, 15) != CompactUnknowns.BLOCK
                || !restored.chunkTileEntityMap.isEmpty() || CompactUnknowns.data(restored).size() != 65536
                || !identity.equals(CompactUnknowns.data(restored).get(65535)))
            throw new AssertionError("Native Anvil save/load lost packed terrain without streaming");
        // Save the decoded chunk again, proving the extension survives a second native cycle.
        loader.saveChunk(world, restored);
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        CompactUnknowns.clear();
        Chunk twice = loader.loadChunk(world, -2, 3);
        if (!identity.equals(CompactUnknowns.data(twice).get(0))) throw new AssertionError("Second native save lost dictionary");
        RegionFileCache.clearRegionFileReferences();
        System.out.println("PASS: actual Anvil save/load twice with streaming=false; 65536 solids, zero tile entities, source identity retained");
        System.out.println("Native region evidence: " + directory);
    }

    public static void main(String[] args) {
        // Vanilla's IO worker belongs to this dedicated test process and does not exit itself.
        try { run(); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
