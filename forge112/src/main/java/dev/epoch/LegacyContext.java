package dev.epoch;
import dev.epoch.bridge.*;
import java.io.IOException;
import net.minecraft.world.WorldServer;
import net.minecraft.util.math.BlockPos;
/** Loaded source context only: never generates a neighbor while capturing a chunk. */
final class LegacyContext {
 static void capture(Snapshot snapshot,final WorldServer world)throws IOException {
  BlockContext.capture(snapshot,new BlockContext.Lookup(){
   private final java.util.Map<net.minecraft.block.state.IBlockState,String> names=new java.util.IdentityHashMap<>();
   public String state(int x,int y,int z)throws IOException {if(y<0||y>=256)return "{Name:\"minecraft:air\"}"; BlockPos pos=new BlockPos(x,y,z);if(!world.isBlockLoaded(pos))return null;if(world.getBlockState(pos).getBlock()==Epoch.UNKNOWN){net.minecraft.tileentity.TileEntity tile=world.getTileEntity(pos);if(tile instanceof Epoch.UnknownTile&&!((Epoch.UnknownTile)tile).originalState.isEmpty())return ((Epoch.UnknownTile)tile).originalState;}net.minecraft.block.state.IBlockState state=world.getBlockState(pos);String raw=names.get(state);if(raw==null){raw=LegacyStates.encode(state);names.put(state,raw);}return raw;}
   public String instrument(int x,int y,int z)throws IOException {if(y<0||y>=256||!world.isBlockLoaded(new BlockPos(x,y,z)))return null; net.minecraft.block.state.IBlockState state=world.getBlockState(new BlockPos(x,y,z)); net.minecraft.block.Block block=state.getBlock();if(block==Epoch.UNKNOWN)return null;
    if(block==net.minecraft.init.Blocks.CLAY)return "flute";if(block==net.minecraft.init.Blocks.GOLD_BLOCK)return "bell";if(block==net.minecraft.init.Blocks.WOOL)return "guitar";if(block==net.minecraft.init.Blocks.PACKED_ICE)return "chime";if(block==net.minecraft.init.Blocks.BONE_BLOCK)return "xylophone";
    net.minecraft.block.material.Material material=state.getMaterial();if(material==net.minecraft.block.material.Material.ROCK)return "basedrum";if(material==net.minecraft.block.material.Material.SAND)return "snare";if(material==net.minecraft.block.material.Material.GLASS)return "hat";if(material==net.minecraft.block.material.Material.WOOD)return "bass";return "harp";}
   public boolean solid(int x,int y,int z)throws IOException {if(y<0||y>=256)return false; BlockPos pos=new BlockPos(x,y,z);if(!world.isBlockLoaded(pos)||world.getBlockState(pos).getBlock()==Epoch.UNKNOWN)throw BlockContext.unavailable();return world.getBlockState(pos).isNormalCube();}
  });
  BlockPayloads.project(snapshot,"canonical");
 }
 static void capture(ChunkSnapshot chunk,WorldServer world)throws IOException {Snapshot snapshot=chunk.blocks();capture(snapshot,world);chunk.palette=snapshot.palette;chunk.runs=snapshot.runs;chunk.blockEntities=snapshot.blockEntities;}
}
