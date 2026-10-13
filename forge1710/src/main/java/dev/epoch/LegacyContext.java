package dev.epoch;
import dev.epoch.bridge.*;
import java.io.IOException;
import net.minecraft.world.WorldServer;
/** Loaded source context only: never generates a neighbor while capturing a chunk. */
final class LegacyContext {
 static void capture(Snapshot snapshot,final WorldServer world)throws IOException {
  BlockContext.capture(snapshot,new BlockContext.Lookup(){
   public String state(int x,int y,int z)throws IOException {if(y<0||y>=256)return "{Name:\"minecraft:air\"}"; if(!world.blockExists(x,y,z))return null; if(world.getBlock(x,y,z)==CompactUnknowns.BLOCK)return CompactUnknowns.require(world,x,y,z).state; if(world.getBlock(x,y,z)==Epoch.UNKNOWN){net.minecraft.tileentity.TileEntity tile=world.getTileEntity(x,y,z);if(tile instanceof Epoch.UnknownTile&&!((Epoch.UnknownTile)tile).originalState.isEmpty())return ((Epoch.UnknownTile)tile).originalState;}return LegacyStates.encode(world.getBlock(x,y,z),world.getBlockMetadata(x,y,z));}
   public String instrument(int x,int y,int z)throws IOException {if(y<0||y>=256||!world.blockExists(x,y,z))return null; if(world.getBlock(x,y,z)==Epoch.UNKNOWN||world.getBlock(x,y,z)==CompactUnknowns.BLOCK)return null; net.minecraft.block.material.Material material=world.getBlock(x,y,z).getMaterial();
    if(material==net.minecraft.block.material.Material.rock)return "basedrum";if(material==net.minecraft.block.material.Material.sand)return "snare";if(material==net.minecraft.block.material.Material.glass)return "hat";if(material==net.minecraft.block.material.Material.wood)return "bass";return "harp";}
   public boolean solid(int x,int y,int z)throws IOException {if(y<0||y>=256)return false; if(!world.blockExists(x,y,z)||world.getBlock(x,y,z)==Epoch.UNKNOWN||world.getBlock(x,y,z)==CompactUnknowns.BLOCK)throw BlockContext.unavailable();return world.getBlock(x,y,z).isNormalCube();}
  });
  BlockPayloads.project(snapshot,"canonical");
 }
 static void capture(ChunkSnapshot chunk,WorldServer world)throws IOException {Snapshot snapshot=chunk.blocks();capture(snapshot,world);chunk.palette=snapshot.palette;chunk.runs=snapshot.runs;chunk.blockEntities=snapshot.blockEntities;}
}
