package dev.epoch;

import dev.epoch.bridge.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.nbt.*;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Real native sections plus compact save/load, no renderer or game instance. */
final class CompactUnknownsTest {
    static void run() throws Exception {
        Block.blockRegistry.addObject(3000,"epoch:packed_unknown",CompactUnknowns.BLOCK);
        UnknownTileLifecycleTest.TestWorld world=new UnknownTileLifecycleTest.TestWorld();
        Chunk chunk=new Chunk(world,-2,3);
        for(int y=0;y<16;y++)chunk.getBlockStorageArray()[y]=new ExtendedBlockStorage(y*16,true);
        ChunkSnapshot source=new ChunkSnapshot();source.chunkX=-2;source.chunkZ=3;source.version="1.20.1";source.sourceInstance="source-with-resource-pack";
        source.palette.add("{Name:\"minecraft:granite\"}");source.runs=new int[]{0,65536};
        String[] biomes=new String[16384];Arrays.fill(biomes,"minecraft:plains");source.biomes=WorldExtras.Biomes.of(biomes);
        long start=System.nanoTime();TerrainTransfer.Prepared prepared=TerrainTransfer.prepare(null,source);
        LegacySelfTest.check(prepared.entities.isEmpty(),"Solid placeholders still created tile entities");
        LegacySelfTest.check(prepared.packed.size()==65536,"Packed preparation lost cells");
        TerrainTransfer.apply(null,chunk,prepared);
        LegacySelfTest.check(chunk.chunkTileEntityMap.isEmpty() && chunk.getBlock(15,255,15)==CompactUnknowns.BLOCK,"Native compact application failed");
        NBTTagCompound saved=new NBTTagCompound();CompactUnknowns.save(chunk,saved);
        LegacySelfTest.check(saved.getByteArray(CompactUnknowns.TAG).length<263000,"Dense state was not palette compressed");
        Chunk reloaded=new Chunk(world,-2,3);reloaded.getBlockStorageArray()[0]=new ExtendedBlockStorage(0,true);
        CompactUnknowns.load(reloaded,saved);
        PackedBlocks.Entry entry=CompactUnknowns.data(reloaded).get(65535);
        LegacySelfTest.check(entry.state.equals(source.palette.get(0))&&entry.instance.equals(source.sourceInstance),"Native compact NBT lost state/provenance");
        net.minecraft.item.ItemStack item=CompactUnknowns.item(entry);
        Epoch.UnknownTile restored=new Epoch.UnknownTile();restored.readFromItem(item);
        LegacySelfTest.check(restored.originalState.equals(entry.state)&&restored.sourceInstance.equals(entry.instance),"Drop/clone did not retain restorable identity");
        PackedBlocks edited=CompactUnknowns.data(reloaded).with(65535,null);
        CompactUnknowns.install(reloaded,edited);NBTTagCompound after=new NBTTagCompound();CompactUnknowns.save(reloaded,after);
        CompactUnknowns.load(reloaded,after);LegacySelfTest.check(CompactUnknowns.data(reloaded).get(65535)==null,"Deleted placeholder resurrected");
        NBTTagCompound old=new NBTTagCompound();NBTTagList tiles=new NBTTagList();NBTTagCompound tile=new NBTTagCompound();
        tile.setString("id","epoch:unknown_block");tile.setInteger("x",-32);tile.setInteger("y",0);tile.setInteger("z",48);tile.setString("OriginalState",entry.state);tile.setString("OriginalNbt","");tile.setString("SourceVersion",entry.version);tile.setString("SourceInstance",entry.instance);tiles.appendTag(tile);old.setTag("TileEntities",tiles);
        CompactUnknowns.load(reloaded,old);
        LegacySelfTest.check(old.getTagList("TileEntities",10).tagCount()==0&&CompactUnknowns.data(reloaded).get(0)!=null,"Old tile migration failed");
        LegacySelfTest.check(!PackedBlocks.eligible(entry.state,"{retained:1b}")&&!PackedBlocks.eligible("{Name:\"minecraft:oak_leaves\"}",""),"Packed migration swallowed NBT or non-solid geometry");
        System.out.println("PASS: 65536 solid unknown blocks, zero tile entities, "+saved.getByteArray(CompactUnknowns.TAG).length+" native bytes; migration/delete/drop/provenance; "+((System.nanoTime()-start)/1000000)+" ms");
        // Known non-entity runs may be filled in bulk, but a payload must still fall back.
        ChunkSnapshot payloadRun=source.copy();payloadRun.palette.set(0,"{Name:\"minecraft:stone\"}");
        payloadRun.blockEntities.put("257","{id:\"minecraft:chest\",retained:7b}");
        TerrainTransfer.Prepared rejected=TerrainTransfer.prepare(null,payloadRun);
        LegacySelfTest.check(rejected.entities.size()==1&&rejected.packed.size()==0,"Run fill bypassed incompatible block payload");
        Epoch.UnknownTile retained=(Epoch.UnknownTile)rejected.entities.values().iterator().next();
        LegacySelfTest.check(retained.originalNbt.contains("retained:7b"),"Run fill lost source NBT");
        CompactUnknowns.clear();
    }
}
