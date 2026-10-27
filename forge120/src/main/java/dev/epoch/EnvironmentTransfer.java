package dev.epoch;
import dev.epoch.bridge.*;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.network.protocol.game.*;

/** Environment migration is prepared completely before the first world mutation. */
final class EnvironmentTransfer {
 static Path carryPath(MinecraftServer server){return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("epoch/environment.json.gz");}
 static Map<String,Holder<Biome>> registry(ServerLevel level){
  net.minecraft.core.Registry<Biome> registry=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
  Map<String,Holder<Biome>> out=new TreeMap<>();for(ResourceLocation id:registry.keySet())out.put(id.toString(),registry.getHolderOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,id)));return out;
 }
 static String[] cells(ServerLevel level,Snapshot s){
  net.minecraft.core.Registry<Biome> registry=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
  String[] out=new String[s.sizeX*s.sizeZ*(s.sizeY/4)];
  for(int y=0;y<s.sizeY/4;y++)for(int z=0;z<s.sizeZ;z++)for(int x=0;x<s.sizeX;x++)
   out[(y*s.sizeZ+z)*s.sizeX+x]=registry.getKey(level.getChunk((s.x0+x)>>4,(s.z0+z)>>4).getNoiseBiome((s.x0+x)>>2,(s.y0>>2)+y,(s.z0+z)>>2).value()).toString();return out;
 }
 static java.lang.reflect.Field thunderField;
 static java.lang.reflect.Field thunderStorage()throws Exception{
  if(thunderField==null){for(String name:new String[]{"thunderLevel","f_46440_"})try{thunderField=net.minecraft.world.level.Level.class.getDeclaredField(name);thunderField.setAccessible(true);break;}catch(NoSuchFieldException ignored){}
   if(thunderField==null)throw new IOException("Missing native thunder field");}
  return thunderField;
 }
 static float rawThunder(net.minecraft.world.level.Level level)throws Exception{return thunderStorage().getFloat(level);}
 static void capture(MinecraftServer server,ServerLevel level,ServerPlayer p,Snapshot s)throws Exception{
  WorldExtras out=new WorldExtras();WorldExtras.Carry carry=WorldExtras.readCarry(carryPath(server));String[] current=cells(level,s);
  out.biomes=WorldExtras.Biomes.of(StreamingWorld.restoreBiomes(level,s,current,carry));
  net.minecraft.world.level.storage.ServerLevelData info=(net.minecraft.world.level.storage.ServerLevelData)level.getLevelData();out.weather=new WorldExtras.Weather();
  out.weather.raining=info.isRaining();out.weather.thundering=info.isThundering();out.weather.rainTime=Math.max(0,info.getRainTime());
  out.weather.thunderTime=Math.max(0,info.getThunderTime());out.weather.clearTime=Math.max(0,info.getClearWeatherTime());
  out.weather.rainLevel=level.getRainLevel(1);out.weather.thunderLevel=rawThunder(level);
  CompoundTag rules=level.getGameRules().createTag();for(String key:rules.getAllKeys())out.gameRules.put(key,rules.getString(key));
  if(carry!=null)for(Map.Entry<String,String> e:carry.rules.entrySet())if(!out.gameRules.containsKey(e.getKey()))out.gameRules.put(e.getKey(),e.getValue());
  out.player=restorePlayer(exportPlayer(p),carry).toString();s.extras=out;s.schema=2;
 }
 static CompoundTag restorePlayer(CompoundTag current,WorldExtras.Carry carry)throws Exception{
  if(carry==null||carry.playerOriginal==null||carry.playerApplied==null)return current;
  CompoundTag original=TagParser.parseTag(carry.playerOriginal),applied=TagParser.parseTag(carry.playerApplied);
  Map<String,Tag> now=new HashMap<>(),before=new HashMap<>(),after=new HashMap<>();
  for(String key:current.getAllKeys())now.put(key,current.get(key));
  for(String key:original.getAllKeys())before.put(key,original.get(key));
  for(String key:applied.getAllKeys())after.put(key,applied.get(key));
  PlayerCompatibility.restoreMissing(now,before,after);for(Map.Entry<String,Tag> e:now.entrySet())current.put(e.getKey(),e.getValue().copy());return current;
 }
 static CompoundTag exportPlayer(ServerPlayer p)throws Exception{
  if(!p.isAlive()||p.isPassenger()||p.isSleeping())throw new IOException("Finish respawning, dismount and wake up before switching");
  CompoundTag raw=p.saveWithoutId(new CompoundTag()),out=new CompoundTag();
  for(String key:PlayerCompatibility.FIELDS)if(raw.contains(key))out.put(key,raw.get(key).copy());
  out.putInt("playerGameType",p.gameMode.getGameModeForPlayer().getId());
  names(out,false);return out;
 }
 static void effectNames(CompoundTag tag,boolean nativeIds)throws Exception{
  if(nativeIds){
   ResourceLocation id=new ResourceLocation(tag.getString("Id"));
   if(!net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.containsKey(id))throw new IOException("Effect unavailable: "+id);
   if(tag.getInt("Duration")==-1&&!Epoch.VERSION.equals("1.20.1"))throw new IOException("Infinite effect cannot be represented: "+id);
   int numeric=net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getId(net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.get(id));if(numeric>255)throw new IOException("Effect ID exceeds this Tag format: "+id);
   tag.putByte("Id",(byte)numeric);
  }else{
   MobEffectInstance effect=MobEffectInstance.load(tag.copy());if(effect==null)throw new IOException("Unknown native effect");
   tag.putString("Id",net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect()).toString());
  }
  if(tag.contains("HiddenEffect",10))effectNames(tag.getCompound("HiddenEffect"),nativeIds);
 }
 static void names(CompoundTag tag,boolean nativeIds)throws Exception{
  ListTag effects=tag.getList("ActiveEffects",10);for(int n=0;n<effects.size();n++)effectNames(effects.getCompound(n),nativeIds);
  ListTag attributes=tag.getList("Attributes",10);
  for(int n=0;n<attributes.size();n++){
   CompoundTag attribute=attributes.getCompound(n);attribute.putString("Name",PlayerCompatibility.canonicalAttribute(attribute.getString("Name")));
   ListTag modifiers=attribute.getList("Modifiers",10);
   for(int m=0;m<modifiers.size();m++){
    CompoundTag modifier=modifiers.getCompound(m);
    if(nativeIds&&modifier.contains("UUIDMost")){modifier.putUUID("UUID",new UUID(modifier.getLong("UUIDMost"),modifier.getLong("UUIDLeast")));modifier.remove("UUIDMost");modifier.remove("UUIDLeast");}
    else if(!nativeIds&&modifier.hasUUID("UUID")){UUID id=modifier.getUUID("UUID");modifier.remove("UUID");modifier.putLong("UUIDMost",id.getMostSignificantBits());modifier.putLong("UUIDLeast",id.getLeastSignificantBits());}
   }
  }
 }
 static void validateScalars(CompoundTag tag)throws IOException{
  for(String key:Arrays.asList("Health","AbsorptionAmount","Air","Fire","FallDistance","foodLevel","foodTickTimer","foodExhaustionLevel","foodSaturationLevel","XpLevel","XpP","XpTotal","XpSeed","Score","SpawnX","SpawnY","SpawnZ","SpawnAngle","HurtTime","DeathTime","PortalCooldown","playerGameType"))
   if(tag.contains(key)&&(!tag.contains(key,99)||!Double.isFinite(tag.getDouble(key))))throw new IOException("Invalid numeric player field: "+key);
  for(String key:Arrays.asList("ActiveEffects","Attributes","EnderItems","Motion"))if(tag.contains(key)&&!tag.contains(key,9))throw new IOException("Invalid player list: "+key);
  for(String key:Arrays.asList("ActiveEffects","Attributes","EnderItems"))if(tag.contains(key)&&((ListTag)tag.get(key)).size()!=tag.getList(key,10).size())throw new IOException("Player list entries must be compounds: "+key);
  if(tag.contains("abilities")&&!tag.contains("abilities",10))throw new IOException("Invalid abilities");
  CompoundTag abilities=tag.getCompound("abilities");for(String key:Arrays.asList("walkSpeed","flySpeed"))if(abilities.contains(key)&&(!abilities.contains(key,99)||!Double.isFinite(abilities.getDouble(key))||abilities.getDouble(key)<0))throw new IOException("Invalid ability speed");
  if(tag.contains("Motion")){ListTag motion=tag.getList("Motion",6);if(motion.size()!=3)throw new IOException("Motion must contain three doubles");for(int n=0;n<3;n++)if(!Double.isFinite(motion.getDouble(n))||Math.abs(motion.getDouble(n))>10)throw new IOException("Motion exceeds native range");}
  if(tag.getDouble("foodLevel")<0||tag.getDouble("foodLevel")>20||tag.getDouble("foodSaturationLevel")<0||tag.getDouble("foodExhaustionLevel")<0||tag.getDouble("AbsorptionAmount")<0||tag.getDouble("FallDistance")<0)throw new IOException("Invalid hunger/absorption/fall state");
 }
 static CompoundTag preparePlayer(ServerPlayer p,String text)throws Exception{
  CompoundTag canonical=TagParser.parseTag(text);for(String key:canonical.getAllKeys())if(!PlayerCompatibility.FIELDS.contains(key))throw new IOException("Unsupported player field: "+key);
  validateScalars(canonical);
  float health=canonical.getFloat("Health"),xp=canonical.getFloat("XpP");
  if(!Float.isFinite(health)||health<=0||!Float.isFinite(xp)||xp<0||xp>1||canonical.getInt("XpLevel")<0||canonical.getInt("XpTotal")<0)throw new IOException("Invalid player health/experience");
  int mode=canonical.getInt("playerGameType");if(mode<0||mode>3)throw new IOException("Invalid player game mode");
  if(canonical.contains("SpawnDimension")&&!canonical.getString("SpawnDimension").equals("minecraft:overworld"))throw new IOException("Respawn dimension outside shared Overworld");
  ListTag ender=canonical.getList("EnderItems",10);Set<Integer> slots=new HashSet<>();
  for(int n=0;n<ender.size();n++){CompoundTag stack=ender.getCompound(n);int slot=stack.getByte("Slot")&255;if(slot>=27||!slots.add(slot))throw new IOException("Invalid ender chest slot");}
  ender=ItemTransfer.decodeList(ender);
  Epoch.validateInventory(ender);
  CompoundTag nativeTag=canonical.copy();nativeTag.put("EnderItems",ender);names(nativeTag,true);
  ListTag effects=nativeTag.getList("ActiveEffects",10);
  for(int n=0;n<effects.size();n++){CompoundTag expected=effects.getCompound(n);MobEffectInstance decoded=MobEffectInstance.load(expected.copy());if(decoded==null||!Epoch.preservesNbt(expected,decoded.save(new CompoundTag())))throw new IOException("Effect payload cannot roundtrip");}
  ListTag attributes=nativeTag.getList("Attributes",10),accepted=new ListTag();Set<String> seen=new HashSet<>();
  for(int n=0;n<attributes.size();n++){
   CompoundTag attr=attributes.getCompound(n);String name=attr.getString("Name");ResourceLocation id=new ResourceLocation(name);
   // Resolve registry aliases before duplicate and lossless payload checks.
   if(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.containsKey(id)){name=net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getKey(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.get(id)).toString();attr.putString("Name",name);}
   if(!seen.add(name)||!Double.isFinite(attr.getDouble("Base")))throw new IOException("Invalid player attribute");
   if(!net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.containsKey(id)||p.getAttribute(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.get(id))==null){
    if(PlayerCompatibility.absentDefault(name,attr.getDouble("Base"),!attr.getList("Modifiers",10).isEmpty()))continue;
    throw new IOException("Player attribute unavailable: "+name);
   }
   net.minecraft.world.entity.ai.attributes.AttributeInstance fresh=new net.minecraft.world.entity.ai.attributes.AttributeInstance(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.get(id),ignored->{});fresh.load(attr.copy());
   if(!Epoch.preservesNbt(attr,fresh.save()))throw new IOException("Attribute payload cannot roundtrip: "+name);
   accepted.add(attr);
  }
  nativeTag.put("Attributes",accepted);return nativeTag;
 }
 static final class Prepared {
  final WorldExtras extra;final CompoundTag player,rules;final String[] original,applied;final Map<String,Holder<Biome>> biomes;final WorldExtras.Carry carry;
  Prepared(WorldExtras extra,CompoundTag player,CompoundTag rules,String[] original,String[] applied,Map<String,Holder<Biome>> biomes,WorldExtras.Carry carry){this.extra=extra;this.player=player;this.rules=rules;this.original=original;this.applied=applied;this.biomes=biomes;this.carry=carry;}
 }
 static Prepared prepare(MinecraftServer server,ServerLevel level,ServerPlayer p,Snapshot s)throws Exception{
  s.validate();if(s.extras==null)return null;
  Map<String,Holder<Biome>> biomes=registry(level);String[] original=s.extras.biomes.expand(s.sizeX*s.sizeZ*(s.sizeY/4));
  String[] applied=BiomeCompatibility.project(original,s.sizeX,s.sizeY/4,s.sizeZ,true,biomes.keySet());
  CompoundTag nativeRules=level.getGameRules().createTag();WorldExtras.Carry carry=new WorldExtras.Carry();carry.bounds(s);
  for(Map.Entry<String,String> e:s.extras.gameRules.entrySet()){
   if(nativeRules.contains(e.getKey())){WorldExtras.validateRule(e.getKey(),e.getValue(),nativeRules.getString(e.getKey()));nativeRules.putString(e.getKey(),e.getValue());}
   else carry.rules.put(e.getKey(),e.getValue());
  }
  carry.original=s.extras.biomes;carry.applied=WorldExtras.Biomes.of(applied);
  return new Prepared(s.extras,preparePlayer(p,s.extras.player),nativeRules,original,applied,biomes,carry);
 }
 static void apply(MinecraftServer server,ServerLevel level,ServerPlayer p,Snapshot s,Prepared prepared)throws Exception{
  if(prepared==null){Files.deleteIfExists(carryPath(server));return;}
  for(int cz=s.minChunkZ();cz<=s.maxChunkZ();cz++)for(int cx=s.minChunkX();cx<=s.maxChunkX();cx++){
   net.minecraft.world.level.chunk.LevelChunk chunk=level.getChunk(cx,cz);
   chunk.fillBiomesFromNoise((x,y,z,sampler)->{
    if(y<0||y>=64)return chunk.getNoiseBiome(x,y,z);
    return prepared.biomes.get(prepared.applied[(y*s.sizeZ+z*4-s.z0)*s.sizeX+x*4-s.x0]);
   },null);chunk.setUnsaved(true);
  }
  level.getGameRules().assignFrom(new GameRules(new com.mojang.serialization.Dynamic<>(NbtOps.INSTANCE,prepared.rules)),server);
  net.minecraft.world.level.storage.ServerLevelData info=(net.minecraft.world.level.storage.ServerLevelData)level.getLevelData();WorldExtras.Weather weather=prepared.extra.weather;
  info.setRaining(weather.raining);info.setThundering(weather.thundering);info.setRainTime(weather.rainTime);info.setThunderTime(weather.thunderTime);info.setClearWeatherTime(weather.clearTime);
  level.setRainLevel(weather.rainLevel);level.setThunderLevel(weather.thunderLevel);
  p.connection.send(new ClientboundGameEventPacket(weather.raining?ClientboundGameEventPacket.START_RAINING:ClientboundGameEventPacket.STOP_RAINING,0));
  p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,weather.rainLevel));p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,weather.thunderLevel));
  // Remove old effects and only persisted attribute modifiers; equipment modifiers remain native.
  p.removeAllEffects();CompoundTag before=p.saveWithoutId(new CompoundTag());ListTag oldAttributes=before.getList("Attributes",10);
  for(int n=0;n<oldAttributes.size();n++){
   CompoundTag attr=oldAttributes.getCompound(n);net.minecraft.world.entity.ai.attributes.Attribute type=net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.get(new ResourceLocation(attr.getString("Name")));
   if(type==null||p.getAttribute(type)==null)continue;p.getAttribute(type).setBaseValue(type.getDefaultValue());
   ListTag mods=attr.getList("Modifiers",10);for(int m=0;m<mods.size();m++)if(mods.getCompound(m).hasUUID("UUID"))p.getAttribute(type).removePermanentModifier(mods.getCompound(m).getUUID("UUID"));
  }
  p.setGameMode(net.minecraft.world.level.GameType.byId(prepared.player.getInt("playerGameType")));
  CompoundTag merged=p.saveWithoutId(new CompoundTag());for(String key:PlayerCompatibility.FIELDS){merged.remove(key);if(prepared.player.contains(key))merged.put(key,prepared.player.get(key).copy());}
  p.setRespawnPosition(net.minecraft.world.level.Level.OVERWORLD,null,0,false,false);p.load(merged);p.getAbilities().loadSaveData(merged);p.onUpdateAbilities();
  p.connection.send(new ClientboundSetHealthPacket(p.getHealth(),p.getFoodData().getFoodLevel(),p.getFoodData().getSaturationLevel()));
  p.connection.send(new ClientboundSetExperiencePacket(p.experienceProgress,p.totalExperience,p.experienceLevel));
  for(MobEffectInstance effect:p.getActiveEffects())p.connection.send(new ClientboundUpdateMobEffectPacket(p.getId(),effect));
  p.connection.send(new ClientboundSetEntityMotionPacket(p));
  prepared.carry.playerOriginal=prepared.extra.player;prepared.carry.playerApplied=exportPlayer(p).toString();
  StreamingWorld.writeCarry(level,carryPath(server),prepared.carry);
  p.connection.send(new ClientboundSetTimePacket(s.gameTime,s.dayTime,level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
  Epoch.bridge().diagnostics.put("environment",WorldExtras.diagnostics(prepared.carry));
 }
}
