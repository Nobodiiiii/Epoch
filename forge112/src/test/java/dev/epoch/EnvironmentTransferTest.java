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
  net.minecraft.init.Bootstrap.register();ItemTransferTest.register();
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
  NBTTagCompound original=attr.copy();EnvironmentTransfer.names(attr,true);EnvironmentTransfer.names(attr,false);check(attr.equals(original),"attribute name/UUID roundtrip changed");
  WorldExtras.Carry carry=new WorldExtras.Carry();carry.playerOriginal="{XpSeed:123,ActiveEffects:[{Id:'minecraft:speed'}]}";carry.playerApplied="{ActiveEffects:[{Id:'minecraft:speed'}]}";
  NBTTagCompound restored=EnvironmentTransfer.restorePlayer(EnvironmentTransfer.parse("{}"),carry);check(restored.getInteger("XpSeed")==123&&!restored.hasKey("ActiveEffects"),"player residual resurrected expired effects");
  check(EnvironmentTransfer.registry().containsKey("minecraft:plains"),"legacy biome fallback missing");
  ChunkSnapshot terrain=new ChunkSnapshot();terrain.version="1.20.1";terrain.chunkX=-100;terrain.chunkZ=300;
  terrain.palette.add("{Name:'minecraft:chest',Properties:{facing:'north',type:'single',waterlogged:'false'}}");
  terrain.palette.add("{Name:'minecraft:air'}");terrain.runs=new int[]{0,1,1,ChunkSnapshot.VOLUME-1};
  terrain.blockEntities.put("0","{id:'minecraft:chest',Items:[{Slot:0b,id:'minecraft:diamond',Count:7b}]}");
  String[] biomes=new String[16384];java.util.Arrays.fill(biomes,"minecraft:plains");biomes[0]="minecraft:cherry_grove";
  terrain.biomes=WorldExtras.Biomes.of(biomes);
  TerrainTransfer.Prepared converted=TerrainTransfer.prepare(null,terrain);
  net.minecraft.util.math.BlockPos chestPos=new net.minecraft.util.math.BlockPos(-1600,0,4800);
  net.minecraft.tileentity.TileEntity tile=converted.entities.get(chestPos);
  check(tile instanceof net.minecraft.tileentity.TileEntityChest,"compatible terrain chest became unknown");
  check(tile.getPos().equals(chestPos),"terrain container lost negative chunk coordinates");
  check(((net.minecraft.tileentity.TileEntityChest)tile).getStackInSlot(0).getCount()==7,"terrain container lost item count");
  check(((net.minecraft.tileentity.TileEntityChest)tile).getStackInSlot(0).getItem()==net.minecraft.init.Items.DIAMOND,"terrain container item conversion failed");
  check(converted.cells[1].getBlock()==net.minecraft.init.Blocks.AIR,"terrain RLE introduced an extra block");
  check(java.util.Arrays.equals(biomes,converted.residual().restore(terrain.blocks(),converted.appliedBiomes)),"legacy 2D biome projection lost canonical data");
  NBTTagCompound stale=Snbt.compound("{Level:{TileEntities:[{id:'minecraft:chest',x:-1599,y:0,z:4800}],Entities:[{id:'minecraft:pig'}],TerrainPopulated:0b}}");
  NBTTagCompound nativeTerrain=TerrainTransfer.nativeTag(stale,converted).getCompoundTag("Level");
  NBTTagList nativeContainers=nativeTerrain.getTagList("TileEntities",10);
  check(nativeContainers.tagCount()==1&&nativeContainers.getCompoundTagAt(0).getInteger("x")==-1600,"native projection retained obsolete deferred chest");
  check(stale.getCompoundTag("Level").getTagList("TileEntities",10).getCompoundTagAt(0).getInteger("x")==-1599,"native projection mutated its input before publication");
  check(nativeTerrain.getTagList("Entities",10).tagCount()==1,"terrain projection discarded native entities");
  NBTTagCompound nativeSection=nativeTerrain.getTagList("Sections",10).getCompoundTagAt(0);
  net.minecraft.world.chunk.BlockStateContainer decodedSection=new net.minecraft.world.chunk.BlockStateContainer();
  decodedSection.setDataFromNBT(nativeSection.getByteArray("Blocks"),new net.minecraft.world.chunk.NibbleArray(nativeSection.getByteArray("Data")),nativeSection.hasKey("Add")?new net.minecraft.world.chunk.NibbleArray(nativeSection.getByteArray("Add")):null);
  check(decodedSection.get(0,0,0).getBlock()==net.minecraft.init.Blocks.CHEST&&decodedSection.get(1,0,0).getBlock()==net.minecraft.init.Blocks.AIR,"native section projection changed block positions");
  check(nativeTerrain.getBoolean("TerrainPopulated")&&!nativeTerrain.getBoolean("LightPopulated"),"native projection permits regeneration or stale lighting");
  terrain.palette.clear();terrain.palette.add("{Name:'minecraft:air'}");terrain.runs=new int[]{0,ChunkSnapshot.VOLUME};terrain.blockEntities.clear();
  check(TerrainTransfer.nativeTag(stale,TerrainTransfer.prepare(null,terrain)).getCompoundTag("Level").getTagList("TileEntities",10).isEmpty(),"air projection resurrects deferred container");
  check(EnvironmentTransfer.thunderStorage().getType()==float.class,"raw thunder storage missing");
  ItemTransferTest.run();
  System.out.println("PASS: "+Epoch.VERSION+" native environment "+checks+" checks");
 }
}
