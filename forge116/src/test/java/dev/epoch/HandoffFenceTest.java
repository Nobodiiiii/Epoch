package dev.epoch;

import dev.epoch.bridge.Snapshot;
import io.netty.buffer.Unpooled;
import java.util.function.BooleanSupplier;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.*;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.concurrent.ThreadTaskExecutor;
import net.minecraft.world.chunk.ChunkSection;

/** Native task budget, inventory and chunk section checks; no client/world launch. */
public final class HandoffFenceTest implements HandoffFenceContract.Native {
    static final class Loop extends ThreadTaskExecutor<Runnable> {
        final Thread owner = Thread.currentThread();
        Loop() { super("Epoch frozen input test"); }
        protected Runnable wrapRunnable(Runnable task) { return task; }
        protected boolean shouldRun(Runnable task) { return false; } // Exhausted normal tick budget.
        protected Thread getRunningThread() { return owner; }
        boolean ordinaryPoll() { return pollTask(); }
    }
    final Loop loop = new Loop();
    PlayerInventory inventory;
    ChunkSection section;
    public void enqueue(Runnable task) { loop.execute(task); }
    public void pump(BooleanSupplier done) { loop.managedBlock(done); }
    public void reset() { inventory=new PlayerInventory(null); section=new ChunkSection(64); }
    public void editInventory() { inventory.setItem(8,new ItemStack(Blocks.STONE)); inventory.setItem(10,new ItemStack(Blocks.DIRT,12)); }
    public void placeStone() { section.setBlockState(0,1,0,Blocks.STONE.defaultBlockState()); }
    public Snapshot capture() {
        Snapshot out=new Snapshot(); out.version=Epoch.VERSION; out.x0=out.z0=0; out.y0=65; out.sizeX=out.sizeY=out.sizeZ=1;
        out.palette.add(NBTUtil.writeBlockState(section.getBlockState(0,1,0)).toString()); out.runs=new int[]{0,1};
        out.inventory=inventory.save(new ListNBT()).toString(); return out;
    }
    public void verifyImported(Snapshot snapshot) throws Exception {
        PlayerInventory restored=new PlayerInventory(null);
        restored.load(ItemTransfer.decodeList(JsonToNBT.parseTag("{Items:"+snapshot.inventory+"}").getList("Items",10)));
        if (restored.getItem(8).getItem()!=net.minecraft.item.Item.byBlock(Blocks.STONE)
                || restored.getItem(10).getCount()!=12 || !snapshot.palette.get(0).contains("stone"))
            throw new AssertionError("Saved native inventory/stone was lost");
    }
    public HandoffFence.Marker wire(long id) {
        PacketBuffer data=new PacketBuffer(Unpooled.buffer());
        try { new HandoffFence.Marker(id).encode(data); return HandoffFence.Marker.decode(data); } finally { data.release(); }
    }
    public static void main(String[] args) throws Exception {
        net.minecraft.util.registry.Bootstrap.bootStrap();
        HandoffFenceTest fixture=new HandoffFenceTest();
        boolean[] ran={false};
        HandoffFenceContract.network(() -> fixture.enqueue(() -> ran[0]=true));
        HandoffFenceContract.check(!fixture.loop.ordinaryPoll() && !ran[0],"ordinary polling ignored the frozen native tick budget");
        fixture.pump(() -> ran[0]);
        HandoffFenceContract.check(ran[0],"managed wait failed to process input with an exhausted tick budget");
        HandoffFenceContract.run(Epoch.VERSION,fixture);
    }
}
