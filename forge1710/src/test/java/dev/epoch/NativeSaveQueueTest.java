package dev.epoch;

import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.*;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.*;
import net.minecraft.world.storage.ThreadedFileIOBase;

/** Real transformed Anvil serialization/worker/region roundtrip, without a game or graphics. */
public final class NativeSaveQueueTest {
    private static final int CHUNKS = 128, TILES = 5000;
    private static final String STATE = "{Name:\"minecraft:deepslate\",Properties:{axis:\"y\"}}";

    static final class TestWorld extends World {
        TestWorld() {
            super(null, "native-save-test", new WorldProviderSurface(),
                new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.DEFAULT),
                new net.minecraft.profiler.Profiler());
        }
        protected net.minecraft.world.chunk.IChunkProvider createChunkProvider() { return null; }
        protected int func_152379_p() { return 0; }
        public net.minecraft.entity.Entity getEntityByID(int id) { return null; }
        public void checkSessionLock() {}
    }

    static final class SlowLoader extends AnvilChunkLoader {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final Object lock;
        final List<?> pending;
        volatile int peak;
        SlowLoader(java.io.File directory) throws Exception {
            super(directory);
            Field queue = AnvilChunkLoader.class.getDeclaredField("chunksToRemove"); queue.setAccessible(true);
            Field monitor = AnvilChunkLoader.class.getDeclaredField("syncLockObject"); monitor.setAccessible(true);
            pending = (List<?>)queue.get(this); lock = monitor.get(this);
        }
        protected void addChunkToPending(ChunkCoordIntPair key, NBTTagCompound tag) {
            super.addChunkToPending(key, tag);
            synchronized (lock) { peak = Math.max(peak, pending.size()); }
        }
        public boolean writeNextIO() {
            entered.countDown();
            try { release.await(); Thread.sleep(10); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            return super.writeNextIO();
        }
        int size() { synchronized (lock) { return pending.size(); } }
    }

    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static Chunk dense(World world, int x) {
        Chunk chunk = new Chunk(world, x, 0);
        for (int index = 0; index < TILES; index++) {
            Epoch.UnknownTile tile = new Epoch.UnknownTile();
            tile.xCoord = x * 16 + (index & 15); tile.zCoord = (index >> 4) & 15; tile.yCoord = index >> 8;
            tile.originalState = STATE; tile.originalNbt = "{retained:" + index + "}";
            tile.sourceVersion = "1.20.1"; tile.sourceInstance = "source-120";
            chunk.chunkTileEntityMap.put(new ChunkPosition(tile.xCoord, tile.yCoord, tile.zCoord), tile);
        }
        return chunk;
    }

    private static void failureChecks() throws Exception {
        List<Object> pending = new ArrayList<>(Collections.nCopies(NativeSaveQueue.MAX_PENDING, new Object()));
        Object lock = new Object();
        boolean timedOut = false;
        try { NativeSaveQueue.awaitCapacity(pending, lock, TimeUnit.MILLISECONDS.toNanos(20)); }
        catch (NativeSaveQueue.SaveStalled expected) { timedOut = true; }
        require(timedOut && pending.size() == NativeSaveQueue.MAX_PENDING, "Stalled writer lost pending saves");
        Thread.currentThread().interrupt();
        boolean interrupted = false;
        try { NativeSaveQueue.awaitCapacity(pending, lock); }
        catch (NativeSaveQueue.SaveStalled expected) { interrupted = Thread.currentThread().isInterrupted(); }
        finally { Thread.interrupted(); }
        require(interrupted, "Interrupted save did not stop with interrupt status preserved");
        pending.clear(); NativeSaveQueue.awaitCapacity(pending, lock);
    }

    private static void run() throws Exception {
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),Collections.emptyList());
        net.minecraft.init.Bootstrap.func_151354_b();
        cpw.mods.fml.common.registry.GameRegistry.registerTileEntity(Epoch.UnknownTile.class, "epoch:unknown_block");
        failureChecks();
        TestWorld world = new TestWorld();
        java.io.File directory = Files.createTempDirectory("epoch-native-save-").toFile();
        SlowLoader loader = new SlowLoader(directory);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try { for (int x = 1; x < CHUNKS; x++) loader.saveChunk(world, dense(world, x)); }
            catch (Throwable error) { failure.set(error); }
        }, "Dense native save producer");
        try {
            loader.saveChunk(world, dense(world, 0));
            require(loader.entered.await(5, TimeUnit.SECONDS), "Native writer did not start");
            producer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (loader.size() < NativeSaveQueue.MAX_PENDING && producer.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            Thread.sleep(250);
            require(loader.size() == NativeSaveQueue.MAX_PENDING && producer.isAlive(), "Native queue was not bounded before serialization: " + loader.size());
        } finally { loader.release.countDown(); }
        producer.join(60000);
        require(!producer.isAlive(), "Native saving did not resume after IO recovered");
        if (failure.get() != null) throw new AssertionError("Native save failed", failure.get());
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        require(loader.peak <= NativeSaveQueue.MAX_PENDING, "Native queue exceeded bound: " + loader.peak);
        for (int x = 0; x < CHUNKS; x++) {
            try (DataInputStream input = RegionFileCache.getChunkInputStream(directory, x, 0)) {
                require(input != null, "Missing saved chunk " + x);
                NBTTagCompound level = CompressedStreamTools.read(input).getCompoundTag("Level");
                NBTTagList tiles = level.getTagList("TileEntities", 10);
                require(level.getInteger("xPos") == x && tiles.tagCount() == TILES, "Native chunk/tiles lost at " + x);
                for (int i = 0; i < TILES; i++) {
                    NBTTagCompound tile = tiles.getCompoundTagAt(i);
                    int index = (tile.getInteger("y") * 16 + tile.getInteger("z")) * 16 + (tile.getInteger("x") & 15);
                    require(tile.getString("OriginalState").equals(STATE)
                        && tile.getString("OriginalNbt").equals("{retained:" + index + "}")
                        && tile.getString("SourceVersion").equals("1.20.1")
                        && tile.getString("SourceInstance").equals("source-120"), "Placeholder payload changed");
                }
            }
        }
        RegionFileCache.clearRegionFileReferences();
        System.out.println("PASS: transformed native save queue peak=" + loader.peak + ", " + CHUNKS
            + " chunks / " + (CHUNKS * TILES) + " placeholders roundtripped with 256 MiB heap; stalled/interrupted writer checks");
        System.out.println("Native region evidence: " + directory);
    }

    public static void main(String[] args) {
        // Vanilla's File IO Thread never exits; this dedicated test JVM owns it.
        try { run(); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
