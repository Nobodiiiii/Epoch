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
  net.minecraft.util.registry.Bootstrap.bootStrap();
  CompoundNBT prepared=EnvironmentTransfer.preparePlayer(null,BASE);check(prepared.getFloat("Health")==18&&prepared.getInt("foodLevel")==14,"scalar state changed");
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("18f","0f")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("0.1d","11d")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("playerGameType:0","playerGameType:4")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("EnderItems:[]","EnderItems:[{Slot:27b,id:'minecraft:stone',Count:1b}]")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("EnderItems:[]","EnderItems:[{Slot:0b,id:'minecraft:stone',Count:1b},{Slot:0b,id:'minecraft:stone',Count:1b}]")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("ActiveEffects:[]","ActiveEffects:[{Id:'missing:effect',Duration:20,Amplifier:0b}]")));
  CompoundNBT effect=JsonToNBT.parseTag("{Id:'minecraft:speed',Duration:200,Amplifier:1b,Ambient:0b}");
  EnvironmentTransfer.effectNames(effect,true);check(effect.contains("Id",1),"effect is not native byte ID");EnvironmentTransfer.effectNames(effect,false);check(effect.getString("Id").equals("minecraft:speed"),"effect registry name changed");
  String withEffect=BASE.replace("ActiveEffects:[]","ActiveEffects:[{Id:'minecraft:speed',Duration:200,Amplifier:1b,Ambient:0b}]");EnvironmentTransfer.preparePlayer(null,withEffect);checks++;
  if(!Epoch.VERSION.equals("1.20.1"))rejects(()->EnvironmentTransfer.preparePlayer(null,withEffect.replace("Duration:200","Duration:-1")));
  CompoundNBT attr=JsonToNBT.parseTag("{Attributes:[{Name:'minecraft:generic.max_health',Base:20d,Modifiers:[{Name:'test',Amount:1d,Operation:0,UUIDMost:123L,UUIDLeast:456L}]}]}");
  CompoundNBT original=attr.copy();EnvironmentTransfer.names(attr,true);EnvironmentTransfer.names(attr,false);check(attr.equals(original),"attribute name/UUID roundtrip changed");
  WorldExtras.Carry carry=new WorldExtras.Carry();carry.playerOriginal="{XpSeed:123,ActiveEffects:[{Id:'minecraft:speed'}]}";carry.playerApplied="{ActiveEffects:[{Id:'minecraft:speed'}]}";
  CompoundNBT restored=EnvironmentTransfer.restorePlayer(JsonToNBT.parseTag("{}"),carry);check(restored.getInt("XpSeed")==123&&!restored.contains("ActiveEffects"),"player residual resurrected expired effects");
  check(EnvironmentTransfer.biomeStorage().getType()==net.minecraft.world.biome.Biome[].class,"native biome storage field changed");
  net.minecraft.world.chunk.Chunk terrain=new net.minecraft.world.chunk.Chunk(null,new net.minecraft.util.math.ChunkPos(-100,300),null);
  net.minecraft.util.math.BlockPos pendingPos=new net.minecraft.util.math.BlockPos(-1597,64,4805);
  CompoundNBT pendingChest=JsonToNBT.parseTag("{id:'minecraft:chest',x:-1597,y:64,z:4805,Items:[]}");
  terrain.setBlockEntityNbt(pendingChest);
  check(TerrainTransfer.pending(terrain).get(pendingPos).equals(pendingChest),"terrain adapter cannot access native pending container NBT");
  TerrainTransfer.pending(terrain).remove(pendingPos);
  check(!terrain.getBlockEntitiesPos().contains(pendingPos),"pending container would resurrect after deletion");
  check(EnvironmentTransfer.thunderStorage().getType()==float.class,"raw thunder storage missing");
  ItemTransferTest.run();
  System.out.println("PASS: "+Epoch.VERSION+" native environment "+checks+" checks");
 }
}
