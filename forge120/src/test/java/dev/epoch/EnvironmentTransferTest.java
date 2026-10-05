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
  new net.minecraftforge.network.NetworkEvent(() -> null).getListenerList();new net.minecraftforge.network.NetworkEvent.GatherLoginPayloadsEvent(new java.util.ArrayList<>(),false).getListenerList();net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  CompoundTag prepared=EnvironmentTransfer.preparePlayer(null,BASE);check(prepared.getFloat("Health")==18&&prepared.getInt("foodLevel")==14,"scalar state changed");
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("18f","0f")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("0.1d","11d")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("playerGameType:0","playerGameType:4")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("EnderItems:[]","EnderItems:[{Slot:27b,id:'minecraft:stone',Count:1b}]")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("EnderItems:[]","EnderItems:[{Slot:0b,id:'minecraft:stone',Count:1b},{Slot:0b,id:'minecraft:stone',Count:1b}]")));
  rejects(()->EnvironmentTransfer.preparePlayer(null,BASE.replace("ActiveEffects:[]","ActiveEffects:[{Id:'missing:effect',Duration:20,Amplifier:0b}]")));
  CompoundTag effect=TagParser.parseTag("{Id:'minecraft:speed',Duration:200,Amplifier:1b,Ambient:0b}");
  EnvironmentTransfer.effectNames(effect,true);check(effect.contains("Id",1),"effect is not native byte ID");EnvironmentTransfer.effectNames(effect,false);check(effect.getString("Id").equals("minecraft:speed"),"effect registry name changed");
  String withEffect=BASE.replace("ActiveEffects:[]","ActiveEffects:[{Id:'minecraft:speed',Duration:200,Amplifier:1b,Ambient:0b}]");EnvironmentTransfer.preparePlayer(null,withEffect);checks++;
  if(!Epoch.VERSION.equals("1.20.1"))rejects(()->EnvironmentTransfer.preparePlayer(null,withEffect.replace("Duration:200","Duration:-1")));
  CompoundTag attr=TagParser.parseTag("{Attributes:[{Name:'minecraft:generic.max_health',Base:20d,Modifiers:[{Name:'test',Amount:1d,Operation:0,UUIDMost:123L,UUIDLeast:456L}]}]}");
  CompoundTag original=attr.copy();EnvironmentTransfer.names(attr,true);EnvironmentTransfer.names(attr,false);check(attr.equals(original),"attribute name/UUID roundtrip changed");
  WorldExtras.Carry carry=new WorldExtras.Carry();carry.playerOriginal="{XpSeed:123,ActiveEffects:[{Id:'minecraft:speed'}]}";carry.playerApplied="{ActiveEffects:[{Id:'minecraft:speed'}]}";
  CompoundTag restored=EnvironmentTransfer.restorePlayer(TagParser.parseTag("{}"),carry);check(restored.getInt("XpSeed")==123&&!restored.contains("ActiveEffects"),"player residual resurrected expired effects");
  
  WorldExtras.Carry legacy=new WorldExtras.Carry();Snapshot bounds=new Snapshot();legacy.bounds(bounds);
  String[] originalBiomes=new String[bounds.sizeX*bounds.sizeZ*64],appliedBiomes=new String[originalBiomes.length];
  java.util.Arrays.fill(originalBiomes,"minecraft:cherry_grove");java.util.Arrays.fill(appliedBiomes,"minecraft:plains");
  legacy.original=WorldExtras.Biomes.of(originalBiomes);legacy.applied=WorldExtras.Biomes.of(appliedBiomes);
  StreamingWorld.Projection adopted=StreamingWorld.initialProjection("test-world",new ChunkKey(-1,-1),legacy);
  check(adopted.revision==0&&"test-world".equals(adopted.worldId),"adoption has invalid authority identity");
  Snapshot chunkBounds=new Snapshot();chunkBounds.x0=chunkBounds.z0=-16;chunkBounds.sizeX=chunkBounds.sizeZ=16;
  String[] nativeBiomes=new String[16384];java.util.Arrays.fill(nativeBiomes,"minecraft:plains");nativeBiomes[0]="minecraft:desert";
  String[] restoredBiomes=adopted.residual.restore(chunkBounds,nativeBiomes);
  check("minecraft:cherry_grove".equals(restoredBiomes[1]),"adoption lost unknown legacy biome");
  check("minecraft:desert".equals(restoredBiomes[0]),"adoption overwrote edited native biome");
  check(StreamingWorld.initialProjection("test-world",new ChunkKey(100,100),legacy).residual==null,"legacy residual leaked outside old region");
  check(StreamingWorld.initialProjection("test-world",new ChunkKey(0,0),null).residual==null,"fresh world acquired legacy residual");
  check(EnvironmentTransfer.thunderStorage().getType()==float.class,"raw thunder storage missing");
  ItemTransferTest.run();
  System.out.println("PASS: "+Epoch.VERSION+" native environment "+checks+" checks");
 }
}
