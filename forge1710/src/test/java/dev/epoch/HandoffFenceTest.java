package dev.epoch;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import dev.epoch.bridge.Snapshot;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.nio.file.*;
import java.util.Collections;
import net.minecraft.block.Block;
import net.minecraft.entity.player.*;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.network.*;
import net.minecraft.network.play.client.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.*;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Actual 1.7 packet queue, inventory container and placement handler; no game window or user save. */
public final class HandoffFenceTest {
    static int checks;
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe)field.get(null)).allocateInstance(type));
    }

    // Only the world interaction boundary is a fixture. Packet validation/slot updates use vanilla code.
    static final class TestWorld extends WorldServer {
        ExtendedBlockStorage section;
        TestWorld() { super(null, null, "fence-test", 0, null, null); }
        public Block getBlock(int x, int y, int z) { return section.getBlockByExtId(x & 15, y & 15, z & 15); }
        public int getBlockMetadata(int x, int y, int z) { return 0; }
    }
    static final class TestServer extends MinecraftServer {
        TestWorld world;
        TestServer() { super(new File("."), Proxy.NO_PROXY); }
        public WorldServer worldServerForDimension(int dimension) { return world; }
        public boolean isBlockProtected(World w, int x, int y, int z, EntityPlayer p) { return false; }
        public int getBuildLimit() { return 256; }
        protected boolean startServer() { return true; }
        public boolean canStructuresSpawn() { return false; }
        public WorldSettings.GameType getGameType() { return WorldSettings.GameType.CREATIVE; }
        public EnumDifficulty func_147135_j() { return EnumDifficulty.PEACEFUL; }
        public boolean isHardcore() { return false; }
        public int getOpPermissionLevel() { return 4; }
        public boolean func_152363_m() { return false; }
        public boolean isDedicatedServer() { return false; }
        public boolean isCommandBlockEnabled() { return false; }
        public String shareToLAN(WorldSettings.GameType mode, boolean cheats) { return null; }
    }
    static final class Placement extends ItemInWorldManager {
        Placement(World world) { super(world); }
        public boolean isCreative() { return true; }
        public boolean activateBlockOrUseItem(EntityPlayer p, World w, ItemStack stack, int x, int y, int z,
                                             int side, float hitX, float hitY, float hitZ) {
            check(stack != null && stack.getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.stone),
                    "placement did not see the preceding inventory update");
            ((TestWorld)w).section.func_150818_a(x & 15, (y + 1) & 15, z & 15, Blocks.stone);
            return true;
        }
    }
    static final class Connection extends NetHandlerPlayServer {
        Connection(MinecraftServer server, NetworkManager network, EntityPlayerMP player) { super(server, network, player); }
        public void onNetworkTick() {} // Isolate packet processing from unrelated simulation/keepalive timers.
        public void sendPacket(Packet packet) {}
    }

    static FMLProxyPacket marker(long id) {
        FMLProxyPacket packet = new FMLProxyPacket(Unpooled.buffer().writeByte(0).writeLong(id), HandoffFence.CHANNEL);
        packet.setTarget(Side.SERVER);
        return packet;
    }
    static Snapshot capture(EntityPlayerMP player, TestWorld world) throws Exception {
        Snapshot out = new Snapshot(); out.version = "1.7.10";
        out.sizeX = out.sizeY = out.sizeZ = 1; out.x0 = out.z0 = 0; out.y0 = 65;
        out.palette.add(LegacyStates.encode(world.getBlock(0,65,0),0)); out.runs = new int[]{0,1};
        NBTTagCompound inventory = new NBTTagCompound(); inventory.setTag("Items",player.inventory.writeToNBT(new NBTTagList()));
        LegacyNbt.Tag canonical = LegacyNbt.from(inventory); Handoff.convertItems(canonical,true);
        out.inventory = LegacyNbt.encode(LegacyNbt.map(canonical).get("Items"));
        return out;
    }
    static void run() throws Exception {
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new File("."),Collections.emptyList());
        net.minecraft.init.Bootstrap.func_151354_b(); HandoffFence.register();
        TestWorld world = allocate(TestWorld.class); world.section = new ExtendedBlockStorage(64,true);
        world.section.func_150818_a(0,0,0,Blocks.grass);
        TestServer server = allocate(TestServer.class); server.world = world;
        EntityPlayerMP player = allocate(EntityPlayerMP.class); player.worldObj = world;
        player.posX = player.posZ = 0.5; player.posY = 65;
        player.inventory = new InventoryPlayer(player); player.capabilities = new PlayerCapabilities();
        player.inventoryContainer = new ContainerPlayer(player.inventory,false,player); player.openContainer = player.inventoryContainer;
        Field manager = EntityPlayerMP.class.getDeclaredField("theItemInWorldManager"); manager.setAccessible(true);
        manager.set(player,new Placement(world)); player.theItemInWorldManager.thisPlayerMP = player;
        player.inventory.setInventorySlotContents(8,new ItemStack(Blocks.grass)); player.inventory.currentItem = 8;
        NetworkManager network = new NetworkManager(false);
        EmbeddedChannel channel = new EmbeddedChannel(network); network.setConnectionState(EnumConnectionState.PLAY);
        Connection connection = new Connection(server,network,player);
        FMLProxyPacket probe = marker(0);
        check(!probe.hasPriority(),"Forge marker bypasses the vanilla packet queue"); probe.payload().release();

        // Input arrives while the server is busy. More than one native receive pass is required.
        for (int i=0;i<1500;i++) channel.writeInbound(new C09PacketHeldItemChange(8));
        channel.writeInbound(new C10PacketCreativeInventoryAction(44,new ItemStack(Blocks.stone)));
        channel.writeInbound(new C10PacketCreativeInventoryAction(10,new ItemStack(Blocks.dirt,12)));
        channel.writeInbound(new C08PacketPlayerBlockPlacement(0,64,0,1,new ItemStack(Blocks.stone),0.5f,1,0.5f));
        Snapshot before = capture(player,world);
        check(!before.inventory.contains("minecraft:stone") && before.palette.get(0).contains("air"),
                "old export ordering did not reproduce lost inventory/stone");
        int passes = HandoffFence.drain(connection,id -> channel.writeInbound(marker(id)),2000);
        check(passes >= 2,"fence failed to cover the 1001-packet receive limit");
        Snapshot after = capture(player,world);
        Path file = Files.createTempFile("epoch-input-fence-", ".json.gz");
        try {
            after.write(file); Snapshot saved = Snapshot.read(file);
            check(saved.palette.get(0).contains("stone") && saved.inventory.contains("minecraft:stone"),"saved snapshot lost stone");
            NBTTagList imported = Handoff.prepareInventory(saved.inventory);
            player.inventory.readFromNBT(imported);
            check(player.inventory.getStackInSlot(8).getItem() == net.minecraft.item.Item.getItemFromBlock(Blocks.stone)
                    && player.inventory.getStackInSlot(10).stackSize == 12,"snapshot reread lost inventory edits");
        } finally { Files.deleteIfExists(file); }
        final long[] oldId = {0};
        try { HandoffFence.drain(connection,id -> oldId[0]=id,10); throw new AssertionError("missing marker allowed export"); }
        catch (IOException expected) { checks++; }
        try { HandoffFence.drain(connection,id -> channel.writeInbound(marker(oldId[0])),10); throw new AssertionError("stale marker allowed export"); }
        catch (IOException expected) { checks++; }
        check(HandoffFence.drain(connection,id -> channel.writeInbound(marker(id)),2000) > 0,"fence did not recover after timeout");
        channel.close();
        try { HandoffFence.drain(connection,id -> {},10); throw new AssertionError("disconnect allowed export"); }
        catch (IOException expected) { checks++; }
        System.out.println("PASS: " + checks + " input fence checks; old ordering loses queued stone/inventory; ordered export retains both");
    }
    public static void main(String[] args) {
        try { run(); System.exit(0); } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
