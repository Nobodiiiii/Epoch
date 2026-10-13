package dev.epoch;
import dev.epoch.bridge.*;
final class SeamlessModernTest {
 static void run(NativeStates.Context context)throws Exception {
  net.minecraft.nbt.CompoundTag cacheTag=new net.minecraft.nbt.CompoundTag(),lightSection=new net.minecraft.nbt.CompoundTag();
  lightSection.putByte("Y",(byte)-1);byte[] blockLight=new byte[2048];blockLight[19]=37;lightSection.putByteArray("BlockLight",blockLight);
  net.minecraft.nbt.ListTag sections=new net.minecraft.nbt.ListTag();sections.add(lightSection);cacheTag.put("sections",sections);cacheTag.putBoolean("isLightOn",true);
  StreamingWorld.deferCachedLighting(cacheTag);
  if(cacheTag.getBoolean("isLightOn")||lightSection.contains("BlockLight"))throw new AssertionError("Unvalidated cached light reached native decode");
  net.minecraft.nbt.CompoundTag deferred=cacheTag.getCompound("EpochDeferredCachedLight");
  if(!deferred.getBoolean("valid")||deferred.getList("sections",10).getCompound(0).getByte("Y")!=-1||deferred.getList("sections",10).getCompound(0).getByteArray("BlockLight")[19]!=37)throw new AssertionError("Deferred light lost bytes or negative section coordinate");
  String[] states={
   "{Name:\"minecraft:red_bed\",Properties:{facing:\"north\",part:\"head\",occupied:\"false\"}}",
   "{Name:\"minecraft:white_banner\",Properties:{rotation:\"3\"}}",
   "{Name:\"minecraft:skull\",EpochLegacyVersion:\"1.12.2\",EpochLegacyMeta:1}",
   "{Name:\"minecraft:oak_sign\",Properties:{rotation:\"0\"}}",
   "{Name:\"minecraft:mob_spawner\"}",
   "{Name:\"minecraft:potted_cactus\"}"};
  String[] payloads={
   "{id:\"minecraft:bed\",color:5}",
   "{id:\"minecraft:banner\",Base:1,Patterns:[{Pattern:\"bs\",Color:0}]}",
   "{id:\"minecraft:skull\",SkullType:4b,Rot:7b}",
   "{id:\"minecraft:sign\",Text1:\"hello\",Text2:\"\",Text3:\"\",Text4:\"\"}",
   "{id:\"minecraft:mob_spawner\",EntityId:\"Pig\",Delay:17s,SpawnPotentials:[{Type:\"Zombie\",Properties:{},Weight:3}]}",
   "{id:\"minecraft:flower_pot\",Item:38,Data:1}"};
  for(int i=0;i<states.length;i++) {
   BlockPayloads.Pair pair=BlockPayloads.convert(states[i],payloads[i],Epoch.VERSION);
   net.minecraft.world.level.block.state.BlockState state=context.materialize(context.resolve(pair.state));if(state==null)throw new AssertionError("Migrated state rejected: "+pair.state);
   if(pair.payload==null)continue;net.minecraft.core.BlockPos pos=new net.minecraft.core.BlockPos(0,64,0);net.minecraft.world.level.block.entity.BlockEntity entity=((net.minecraft.world.level.block.EntityBlock)state.getBlock()).newBlockEntity(pos,state);
   if(entity==null)throw new AssertionError("Missing migrated entity factory");net.minecraft.nbt.CompoundTag tag=net.minecraft.nbt.TagParser.parseTag(pair.payload);tag.putInt("x",0);tag.putInt("y",64);tag.putInt("z",0);entity.load(tag.copy());
   net.minecraft.nbt.CompoundTag back=entity.saveWithFullMetadata();if(!Epoch.preservesNbt(tag,back))throw new AssertionError("Native payload changed: "+pair.payload+" -> "+back);
  }
  if(context.materialize(context.resolve("{Name:\"minecraft:dirt\",EpochLegacyVersion:\"1.7.10\",EpochLegacyMeta:1}"))==null)throw new AssertionError("Historical dirt rejected");
  if(Epoch.VERSION.equals("1.18.2")&&context.materialize(context.resolve("{Name:\"minecraft:azalea_leaves\",Properties:{waterlogged:\"false\",distance:\"1\",persistent:\"true\"}}"))==null)throw new AssertionError("Dry azalea rejected");
  System.out.println("Modern semantic payload fixtures passed: "+Epoch.VERSION);
 }
}
