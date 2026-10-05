package dev.epoch;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/** Exercises Forge's actual tile registration and unload paths without a client. */
final class UnknownTileLifecycleTest {
    static final class TestWorld extends World {
        TestWorld() {
            super(null, "unknown-tile-test", new net.minecraft.world.WorldProviderSurface(),
                new net.minecraft.world.WorldSettings(0L, net.minecraft.world.WorldSettings.GameType.CREATIVE,
                    false, false, net.minecraft.world.WorldType.DEFAULT), new net.minecraft.profiler.Profiler());
        }
        protected net.minecraft.world.chunk.IChunkProvider createChunkProvider() { return null; }
        protected int func_152379_p() { return 0; }
        public net.minecraft.entity.Entity getEntityByID(int id) { return null; }
    }

    static void run() {
        cpw.mods.fml.common.registry.GameRegistry.registerTileEntity(Epoch.UnknownTile.class, "epoch:unknown_block");
        TestWorld world = new TestWorld();
        TileEntity ticking = new net.minecraft.tileentity.TileEntityChest();
        world.addTileEntity(ticking);
        List<TileEntity> tiles = new ArrayList<>();
        for (int i = 0; i < 66751; i++) {
            Epoch.UnknownTile tile = new Epoch.UnknownTile();
            tile.originalState = "{Name:\"minecraft:deepslate\"}";
            tile.originalNbt = "{retained:1b}";
            tile.sourceVersion = "1.20.1";
            tiles.add(tile);
            world.addTileEntity(tile);
        }
        world.func_147448_a(tiles);
        LegacySelfTest.check(world.loadedTileEntityList.size() == 1
            && world.loadedTileEntityList.contains(ticking), "Payload tiles entered Forge's tick list");
        for (TileEntity tile : tiles) world.func_147457_a(tile);
        world.updateEntities();
        LegacySelfTest.check(world.loadedTileEntityList.size() == 1, "Unloading placeholders removed a ticking tile");
        NBTTagCompound saved = new NBTTagCompound();
        tiles.get(0).writeToNBT(saved);
        Epoch.UnknownTile restored = (Epoch.UnknownTile)TileEntity.createAndLoadEntity(saved);
        NBTTagCompound roundtrip = new NBTTagCompound();
        restored.writeToNBT(roundtrip);
        LegacySelfTest.check(saved.equals(roundtrip), "Non-ticking placeholder lost saved payload");
        LegacySelfTest.check(restored.getDescriptionPacket() != null, "Non-ticking placeholder lost network sync");
        System.out.println("PASS: 66751 placeholder registrations/unloads, NBT reload and description packet");
    }
}
