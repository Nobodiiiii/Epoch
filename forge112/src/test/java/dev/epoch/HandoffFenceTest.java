package dev.epoch;

import dev.epoch.bridge.Snapshot;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

public final class HandoffFenceTest implements HandoffFenceContract.Native {
    final MinecraftServer server;
    InventoryPlayer inventory;
    ExtendedBlockStorage section;
    HandoffFenceTest() throws Exception {
        Field unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafe.setAccessible(true);
        server = (MinecraftServer)((sun.misc.Unsafe)unsafe.get(null)).allocateInstance(IntegratedServer.class);
        Field queue = MinecraftServer.class.getDeclaredField("futureTaskQueue"); queue.setAccessible(true);
        queue.set(server,new ConcurrentLinkedQueue<>());
        Field thread = MinecraftServer.class.getDeclaredField("serverThread"); thread.setAccessible(true);
        thread.set(server,Thread.currentThread());
    }
    public void enqueue(Runnable task) { server.addScheduledTask(task); }
    public void pump(BooleanSupplier done) {
        while (!done.getAsBoolean()) {
            try { Handoff.drainTasks(server); } catch (Exception error) { throw new AssertionError(error); }
            java.util.concurrent.locks.LockSupport.parkNanos(100000L);
        }
    }
    public void reset() { inventory=new InventoryPlayer(null); section=new ExtendedBlockStorage(64,true); }
    public void editInventory() { inventory.setInventorySlotContents(8,new ItemStack(Blocks.STONE)); inventory.setInventorySlotContents(10,new ItemStack(Blocks.DIRT,12)); }
    public void placeStone() { section.set(0,1,0,Blocks.STONE.getDefaultState()); }
    public Snapshot capture() throws Exception {
        Snapshot out=new Snapshot(); out.version=Epoch.VERSION; out.x0=out.z0=0; out.y0=65; out.sizeX=out.sizeY=out.sizeZ=1;
        out.palette.add(LegacyStates.encode(section.get(0,1,0))); out.runs=new int[]{0,1};
        out.inventory=LegacyItems.encodeList(inventory.writeToNBT(new NBTTagList())).toString(); return out;
    }
    public void verifyImported(Snapshot snapshot) throws Exception {
        InventoryPlayer restored=new InventoryPlayer(null);
        restored.readFromNBT(LegacyItems.decodePlayerInventory(Snbt.compound("{Items:"+snapshot.inventory+"}").getTagList("Items",10)));
        if (restored.getStackInSlot(8).getItem()!=net.minecraft.item.Item.getItemFromBlock(Blocks.STONE)
                || restored.getStackInSlot(10).getCount()!=12 || !snapshot.palette.get(0).contains("stone"))
            throw new AssertionError("Saved native inventory/stone was lost");
    }
    public HandoffFence.Marker wire(long id) {
        ByteBuf data=Unpooled.buffer(); try { new HandoffFence.Marker(id).toBytes(data); HandoffFence.Marker read=new HandoffFence.Marker(); read.fromBytes(data); return read; } finally { data.release(); }
    }
    public static void main(String[] args) throws Exception { Bootstrap.register(); HandoffFenceContract.run(Epoch.VERSION,new HandoffFenceTest()); }
}
