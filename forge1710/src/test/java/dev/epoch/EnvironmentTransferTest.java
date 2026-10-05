package dev.epoch;
import net.minecraft.nbt.*;
import dev.epoch.bridge.*;
public final class EnvironmentTransferTest {
 interface Action {void run()throws Exception;}
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void rejects(Action action)throws Exception{checks++;try{action.run();}catch(java.io.IOException|IllegalArgumentException expected){return;}throw new AssertionError("Accepted unsupported payload");}
 static final String BASE="{Health:18f,XpP:0.5f,XpLevel:4,XpTotal:42,foodLevel:14,foodSaturationLevel:3f,playerGameType:0,EnderItems:[],Attributes:[],ActiveEffects:[],Motion:[0.1d,0d,-0.1d]}";
 public static void main(String[] args)throws Exception{
  cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),java.util.Collections.emptyList());net.minecraft.init.Bootstrap.func_151354_b();
  NBTTagCompound prepared=EnvironmentTransfer.preparePlayer(null,BASE);check(prepared.getFloat("Health")==18&&prepared.getInteger("foodLevel")==14,"scalar state changed");
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("18f","0f")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("0.1d","11d")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("playerGameType:0","playerGameType:4")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("EnderItems:[]","EnderItems:[{Slot:27b,id:'minecraft:stone',Count:1b}]")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("EnderItems:[]","EnderItems:[{Slot:0b,id:'minecraft:stone',Count:1b},{Slot:0b,id:'minecraft:stone',Count:1b}]")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("ActiveEffects:[]","ActiveEffects:[{Id:'missing:effect',Duration:20,Amplifier:0b}]")));
  NBTTagCompound effect=EnvironmentTransfer.parse("{Id:'minecraft:speed',Duration:200,Amplifier:1b,Ambient:0b}");
  EnvironmentTransfer.effectNames(effect,true);check(effect.hasKey("Id",1),"effect is not native byte ID");EnvironmentTransfer.effectNames(effect,false);check(effect.getString("Id").equals("minecraft:speed"),"effect registry name changed");
  String withEffect=BASE.replace("ActiveEffects:[]","ActiveEffects:[{Id:'minecraft:speed',Duration:200,Amplifier:1b,Ambient:0b}]");EnvironmentTransfer.preparePlayer(null,withEffect);checks++;
  if(!Epoch.VERSION.equals("1.20.1"))rejects(()->EnvironmentTransfer.preparePlayer(null,withEffect.replace("Duration:200","Duration:-1")));
  NBTTagCompound attr=EnvironmentTransfer.parse("{Attributes:[{Name:'minecraft:generic.max_health',Base:20d,Modifiers:[{Name:'test',Amount:1d,Operation:0,UUIDMost:123L,UUIDLeast:456L}]}]}");
  NBTTagCompound original=(NBTTagCompound)attr.copy();EnvironmentTransfer.names(attr,true);EnvironmentTransfer.names(attr,false);check(attr.equals(original),"attribute name/UUID roundtrip changed");
  WorldExtras.Carry carry=new WorldExtras.Carry();carry.playerOriginal="{XpSeed:123,ActiveEffects:[{Id:'minecraft:speed'}]}";carry.playerApplied="{ActiveEffects:[{Id:'minecraft:speed'}]}";
  NBTTagCompound restored=EnvironmentTransfer.restorePlayer(EnvironmentTransfer.parse("{}"),carry);check(restored.getInteger("XpSeed")==123&&!restored.hasKey("ActiveEffects"),"player residual resurrected expired effects");
  check(EnvironmentTransfer.registry().containsKey("minecraft:plains"),"legacy biome fallback missing");
  ChunkSnapshot terrain=new ChunkSnapshot();terrain.version="1.20.1";terrain.chunkX=-100;terrain.chunkZ=300;
  terrain.palette.add(LegacyStates.encode(net.minecraft.init.Blocks.chest,2));
  terrain.palette.add(LegacyStates.encode(net.minecraft.init.Blocks.wool,14));terrain.palette.add("{Name:'minecraft:air'}");
  terrain.runs=new int[]{0,1,1,1,2,ChunkSnapshot.VOLUME-2};
  terrain.blockEntities.put("0","{id:'minecraft:chest',Items:[{Slot:0b,id:'minecraft:diamond',Count:7b}]}");
  String[] canonicalBiomes=new String[16384];java.util.Arrays.fill(canonicalBiomes,"minecraft:plains");canonicalBiomes[0]="minecraft:cherry_grove";
  terrain.biomes=WorldExtras.Biomes.of(canonicalBiomes);
  TerrainTransfer.Prepared projected=TerrainTransfer.prepare(null,terrain);
  net.minecraft.tileentity.TileEntity tile=projected.entities.get(new net.minecraft.world.ChunkPosition(-1600,0,4800));
  check(tile instanceof net.minecraft.tileentity.TileEntityChest,"compatible chest became a placeholder");
  check(tile.xCoord==-1600&&tile.yCoord==0&&tile.zCoord==4800,"legacy tile coordinates changed");
  net.minecraft.item.ItemStack diamonds=((net.minecraft.tileentity.TileEntityChest)tile).getStackInSlot(0);
  check(diamonds!=null&&diamonds.getItem()==net.minecraft.init.Items.diamond&&diamonds.stackSize==7,"legacy numeric item conversion lost diamonds");
  check(projected.cells[0].meta==2&&projected.cells[1].block==net.minecraft.init.Blocks.wool&&projected.cells[1].meta==14,"legacy direction/color metadata changed");
  check(projected.cells[2].block==net.minecraft.init.Blocks.air,"legacy RLE introduced an extra block");
  check(java.util.Arrays.equals(canonicalBiomes,projected.residual().restore(terrain.blocks(),projected.appliedBiomes)),"legacy biome residual was lost");
  terrain.palette.set(0,"{Name:'minecraft:cherry_planks'}");
  TerrainTransfer.Prepared unknown=TerrainTransfer.prepare(null,terrain);
  Epoch.UnknownTile placeholder=(Epoch.UnknownTile)unknown.entities.get(new net.minecraft.world.ChunkPosition(-1600,0,4800));
  check(placeholder.originalState.contains("cherry_planks")&&placeholder.originalNbt.contains("diamond"),"unknown terrain or opaque NBT was discarded");
  ItemTransferTest.run();
  System.out.println("PASS: "+Epoch.VERSION+" native environment "+checks+" checks");
 }
}
