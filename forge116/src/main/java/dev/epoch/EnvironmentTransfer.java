package dev.epoch;
import dev.epoch.bridge.*;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.*;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.biome.*;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.GameRules;
import net.minecraft.potion.EffectInstance;
import net.minecraft.network.play.server.*;

/** Environment migration is prepared completely before the first world mutation. */
final class EnvironmentTransfer {
 static Path carryPath(MinecraftServer server){return server.getWorldPath(net.minecraft.world.storage.FolderName.ROOT).resolve("epoch/environment.json.gz");}
 static Map<String,Biome> registry(ServerWorld level){
  net.minecraft.util.registry.Registry<Biome> registry=level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
  Map<String,Biome> out=new TreeMap<>();for(ResourceLocation id:registry.keySet())out.put(id.toString(),registry.get(id));return out;
 }
 static String[] cells(ServerWorld level,Snapshot s){
  net.minecraft.util.registry.Registry<Biome> registry=level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
  String[] out=new String[s.sizeX*s.sizeZ*(s.sizeY/4)];
  for(int y=0;y<s.sizeY/4;y++)for(int z=0;z<s.sizeZ;z++)for(int x=0;x<s.sizeX;x++)
   out[(y*s.sizeZ+z)*s.sizeX+x]=registry.getKey(level.getChunk((s.x0+x)>>4,(s.z0+z)>>4).getBiomes().getNoiseBiome((s.x0+x)>>2,(s.y0>>2)+y,(s.z0+z)>>2)).toString();return out;
 }
 static java.lang.reflect.Field thunderField;
 static java.lang.reflect.Field thunderStorage()throws Exception{
  if(thunderField==null){for(String name:new String[]{"thunderLevel","field_73017_q"})try{thunderField=net.minecraft.world.World.class.getDeclaredField(name);thunderField.setAccessible(true);break;}catch(NoSuchFieldException ignored){}
   if(thunderField==null)throw new IOException("Missing native thunder field");}
  return thunderField;
 }
 static float rawThunder(net.minecraft.world.World level)throws Exception{return thunderStorage().getFloat(level);}
 static void capture(MinecraftServer server,ServerWorld level,ServerPlayerEntity p,Snapshot s)throws Exception{
  WorldExtras out=new WorldExtras();WorldExtras.Carry carry=WorldExtras.readCarry(carryPath(server));String[] current=cells(level,s);
  out.biomes=WorldExtras.Biomes.of(StreamingWorld.restoreBiomes(level,s,current,carry));
  net.minecraft.world.storage.IServerWorldInfo info=(net.minecraft.world.storage.IServerWorldInfo)level.getLevelData();out.weather=new WorldExtras.Weather();
  out.weather.raining=info.isRaining();out.weather.thundering=info.isThundering();out.weather.rainTime=Math.max(0,info.getRainTime());
  out.weather.thunderTime=Math.max(0,info.getThunderTime());out.weather.clearTime=Math.max(0,info.getClearWeatherTime());
  out.weather.rainLevel=level.getRainLevel(1);out.weather.thunderLevel=rawThunder(level);
  CompoundNBT rules=level.getGameRules().createTag();for(String key:rules.getAllKeys())out.gameRules.put(key,rules.getString(key));
  if(carry!=null)for(Map.Entry<String,String> e:carry.rules.entrySet())if(!out.gameRules.containsKey(e.getKey()))out.gameRules.put(e.getKey(),e.getValue());
  out.player=restorePlayer(exportPlayer(p),carry).toString();s.extras=out;s.schema=2;
 }
 static CompoundNBT restorePlayer(CompoundNBT current,WorldExtras.Carry carry)throws Exception{
  if(carry==null||carry.playerOriginal==null||carry.playerApplied==null)return current;
  CompoundNBT original=JsonToNBT.parseTag(carry.playerOriginal),applied=JsonToNBT.parseTag(carry.playerApplied);
  Map<String,INBT> now=new HashMap<>(),before=new HashMap<>(),after=new HashMap<>();
  for(String key:current.getAllKeys())now.put(key,current.get(key));
  for(String key:original.getAllKeys())before.put(key,original.get(key));
  for(String key:applied.getAllKeys())after.put(key,applied.get(key));
  PlayerCompatibility.restoreMissing(now,before,after);for(Map.Entry<String,INBT> e:now.entrySet())current.put(e.getKey(),e.getValue().copy());return current;
 }
 static CompoundNBT exportPlayer(ServerPlayerEntity p)throws Exception{
  if(!p.isAlive()||p.isPassenger()||p.isSleeping())throw new IOException("Finish respawning, dismount and wake up before switching");
  CompoundNBT raw=p.saveWithoutId(new CompoundNBT()),out=new CompoundNBT();
  for(String key:PlayerCompatibility.FIELDS)if(raw.contains(key))out.put(key,raw.get(key).copy());
  out.putInt("playerGameType",p.gameMode.getGameModeForPlayer().getId());
  names(out,false);return out;
 }
 static void effectNames(CompoundNBT tag,boolean nativeIds)throws Exception{
  if(nativeIds){
   ResourceLocation id=new ResourceLocation(tag.getString("Id"));
   if(!Registry.MOB_EFFECT.containsKey(id))throw new IOException("Effect unavailable: "+id);
   if(tag.getInt("Duration")==-1&&!Epoch.VERSION.equals("1.20.1"))throw new IOException("Infinite effect cannot be represented: "+id);
   int numeric=Registry.MOB_EFFECT.getId(Registry.MOB_EFFECT.get(id));if(numeric>255)throw new IOException("Effect ID exceeds this INBT format: "+id);
   tag.putByte("Id",(byte)numeric);
  }else{
   EffectInstance effect=EffectInstance.load(tag.copy());if(effect==null)throw new IOException("Unknown native effect");
   tag.putString("Id",Registry.MOB_EFFECT.getKey(effect.getEffect()).toString());
  }
  if(tag.contains("HiddenEffect",10))effectNames(tag.getCompound("HiddenEffect"),nativeIds);
 }
 static void names(CompoundNBT tag,boolean nativeIds)throws Exception{
  ListNBT effects=tag.getList("ActiveEffects",10);for(int n=0;n<effects.size();n++)effectNames(effects.getCompound(n),nativeIds);
  ListNBT attributes=tag.getList("Attributes",10);
  for(int n=0;n<attributes.size();n++){
   CompoundNBT attribute=attributes.getCompound(n);attribute.putString("Name",PlayerCompatibility.canonicalAttribute(attribute.getString("Name")));
   ListNBT modifiers=attribute.getList("Modifiers",10);
   for(int m=0;m<modifiers.size();m++){
    CompoundNBT modifier=modifiers.getCompound(m);
    if(nativeIds&&modifier.contains("UUIDMost")){modifier.putUUID("UUID",new UUID(modifier.getLong("UUIDMost"),modifier.getLong("UUIDLeast")));modifier.remove("UUIDMost");modifier.remove("UUIDLeast");}
    else if(!nativeIds&&modifier.hasUUID("UUID")){UUID id=modifier.getUUID("UUID");modifier.remove("UUID");modifier.putLong("UUIDMost",id.getMostSignificantBits());modifier.putLong("UUIDLeast",id.getLeastSignificantBits());}
   }
  }
 }
 static void validateScalars(CompoundNBT tag)throws IOException{
  for(String key:Arrays.asList("Health","AbsorptionAmount","Air","Fire","FallDistance","foodLevel","foodTickTimer","foodExhaustionLevel","foodSaturationLevel","XpLevel","XpP","XpTotal","XpSeed","Score","SpawnX","SpawnY","SpawnZ","SpawnAngle","HurtTime","DeathTime","PortalCooldown","playerGameType"))
   if(tag.contains(key)&&(!tag.contains(key,99)||!Double.isFinite(tag.getDouble(key))))throw new IOException("Invalid numeric player field: "+key);
  for(String key:Arrays.asList("ActiveEffects","Attributes","EnderItems","Motion"))if(tag.contains(key)&&!tag.contains(key,9))throw new IOException("Invalid player list: "+key);
  for(String key:Arrays.asList("ActiveEffects","Attributes","EnderItems"))if(tag.contains(key)&&((ListNBT)tag.get(key)).size()!=tag.getList(key,10).size())throw new IOException("Player list entries must be compounds: "+key);
  if(tag.contains("abilities")&&!tag.contains("abilities",10))throw new IOException("Invalid abilities");
  CompoundNBT abilities=tag.getCompound("abilities");for(String key:Arrays.asList("walkSpeed","flySpeed"))if(abilities.contains(key)&&(!abilities.contains(key,99)||!Double.isFinite(abilities.getDouble(key))||abilities.getDouble(key)<0))throw new IOException("Invalid ability speed");
  if(tag.contains("Motion")){ListNBT motion=tag.getList("Motion",6);if(motion.size()!=3)throw new IOException("Motion must contain three doubles");for(int n=0;n<3;n++)if(!Double.isFinite(motion.getDouble(n))||Math.abs(motion.getDouble(n))>10)throw new IOException("Motion exceeds native range");}
  if(tag.getDouble("foodLevel")<0||tag.getDouble("foodLevel")>20||tag.getDouble("foodSaturationLevel")<0||tag.getDouble("foodExhaustionLevel")<0||tag.getDouble("AbsorptionAmount")<0||tag.getDouble("FallDistance")<0)throw new IOException("Invalid hunger/absorption/fall state");
 }
 static CompoundNBT preparePlayer(ServerPlayerEntity p,String text)throws Exception{
  CompoundNBT canonical=JsonToNBT.parseTag(text);for(String key:canonical.getAllKeys())if(!PlayerCompatibility.FIELDS.contains(key))throw new IOException("Unsupported player field: "+key);
  validateScalars(canonical);
  float health=canonical.getFloat("Health"),xp=canonical.getFloat("XpP");
  if(!Float.isFinite(health)||health<=0||!Float.isFinite(xp)||xp<0||xp>1||canonical.getInt("XpLevel")<0||canonical.getInt("XpTotal")<0)throw new IOException("Invalid player health/experience");
  int mode=canonical.getInt("playerGameType");if(mode<0||mode>3)throw new IOException("Invalid player game mode");
  if(canonical.contains("SpawnDimension")&&!canonical.getString("SpawnDimension").equals("minecraft:overworld"))throw new IOException("Respawn dimension outside shared Overworld");
  ListNBT ender=canonical.getList("EnderItems",10);Set<Integer> slots=new HashSet<>();
  for(int n=0;n<ender.size();n++){CompoundNBT stack=ender.getCompound(n);int slot=stack.getByte("Slot")&255;if(slot>=27||!slots.add(slot))throw new IOException("Invalid ender chest slot");}
  ender=ItemTransfer.decodeList(ender);
  Handoff.validateInventory(ender);
  CompoundNBT nativeTag=canonical.copy();nativeTag.put("EnderItems",ender);names(nativeTag,true);
  ListNBT effects=nativeTag.getList("ActiveEffects",10);
  for(int n=0;n<effects.size();n++){CompoundNBT expected=effects.getCompound(n);EffectInstance decoded=EffectInstance.load(expected.copy());if(decoded==null||!Handoff.preservesNbt(expected,decoded.save(new CompoundNBT())))throw new IOException("Effect payload cannot roundtrip");}
  ListNBT attributes=nativeTag.getList("Attributes",10),accepted=new ListNBT();Set<String> seen=new HashSet<>();
  for(int n=0;n<attributes.size();n++){
   CompoundNBT attr=attributes.getCompound(n);String name=attr.getString("Name");ResourceLocation id=new ResourceLocation(name);
   // Resolve registry aliases before duplicate and lossless payload checks.
   if(Registry.ATTRIBUTE.containsKey(id)){name=Registry.ATTRIBUTE.getKey(Registry.ATTRIBUTE.get(id)).toString();attr.putString("Name",name);}
   if(!seen.add(name)||!Double.isFinite(attr.getDouble("Base")))throw new IOException("Invalid player attribute");
   if(!Registry.ATTRIBUTE.containsKey(id)||p.getAttribute(Registry.ATTRIBUTE.get(id))==null){
    if(PlayerCompatibility.absentDefault(name,attr.getDouble("Base"),!attr.getList("Modifiers",10).isEmpty()))continue;
    throw new IOException("Player attribute unavailable: "+name);
   }
   net.minecraft.entity.ai.attributes.ModifiableAttributeInstance fresh=new net.minecraft.entity.ai.attributes.ModifiableAttributeInstance(Registry.ATTRIBUTE.get(id),ignored->{});fresh.load(attr.copy());
   if(!Handoff.preservesNbt(attr,fresh.save()))throw new IOException("Attribute payload cannot roundtrip: "+name);
   accepted.add(attr);
  }
  nativeTag.put("Attributes",accepted);return nativeTag;
 }
 static final class Prepared {
  final WorldExtras extra;final CompoundNBT player,rules;final String[] original,applied;final Map<String,Biome> biomes;final WorldExtras.Carry carry;
  Prepared(WorldExtras extra,CompoundNBT player,CompoundNBT rules,String[] original,String[] applied,Map<String,Biome> biomes,WorldExtras.Carry carry){this.extra=extra;this.player=player;this.rules=rules;this.original=original;this.applied=applied;this.biomes=biomes;this.carry=carry;}
 }
 static java.lang.reflect.Field biomeField;
 static java.lang.reflect.Field biomeStorage()throws IOException{
  if(biomeField!=null)return biomeField;
  java.lang.reflect.Field found=null;for(java.lang.reflect.Field candidate:BiomeContainer.class.getDeclaredFields())if(candidate.getType()==Biome[].class){if(found!=null)throw new IOException("Ambiguous biome storage");found=candidate;}
  if(found==null)throw new IOException("Missing biome storage");found.setAccessible(true);biomeField=found;return found;
 }
 static Prepared prepare(MinecraftServer server,ServerWorld level,ServerPlayerEntity p,Snapshot s)throws Exception{
  s.validate();if(s.extras==null)return null;biomeStorage();
  Map<String,Biome> biomes=registry(level);String[] original=s.extras.biomes.expand(s.sizeX*s.sizeZ*(s.sizeY/4));
  String[] applied=BiomeCompatibility.project(original,s.sizeX,s.sizeY/4,s.sizeZ,true,biomes.keySet());
  CompoundNBT nativeRules=level.getGameRules().createTag();WorldExtras.Carry carry=new WorldExtras.Carry();carry.bounds(s);
  for(Map.Entry<String,String> e:s.extras.gameRules.entrySet()){
   if(nativeRules.contains(e.getKey())){WorldExtras.validateRule(e.getKey(),e.getValue(),nativeRules.getString(e.getKey()));nativeRules.putString(e.getKey(),e.getValue());}
   else carry.rules.put(e.getKey(),e.getValue());
  }
  carry.original=s.extras.biomes;carry.applied=WorldExtras.Biomes.of(applied);
  return new Prepared(s.extras,preparePlayer(p,s.extras.player),nativeRules,original,applied,biomes,carry);
 }
 static void apply(MinecraftServer server,ServerWorld level,ServerPlayerEntity p,Snapshot s,Prepared prepared)throws Exception{
  if(prepared==null){Files.deleteIfExists(carryPath(server));return;}
  for(int cz=s.minChunkZ();cz<=s.maxChunkZ();cz++)for(int cx=s.minChunkX();cx<=s.maxChunkX();cx++){
   net.minecraft.world.chunk.Chunk chunk=level.getChunk(cx,cz);java.lang.reflect.Field field=biomeStorage();Biome[] data=(Biome[])field.get(chunk.getBiomes());
   for(int y=0;y<64;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)data[(y<<4)|(z<<2)|x]=prepared.biomes.get(prepared.applied[(y*s.sizeZ+(cz*16+z*4-s.z0))*s.sizeX+(cx*16+x*4-s.x0)]);
   chunk.setUnsaved(true);
  }
  level.getGameRules().assignFrom(new GameRules(new com.mojang.serialization.Dynamic<>(NBTDynamicOps.INSTANCE,prepared.rules)),server);
  net.minecraft.world.storage.IServerWorldInfo info=(net.minecraft.world.storage.IServerWorldInfo)level.getLevelData();WorldExtras.Weather weather=prepared.extra.weather;
  info.setRaining(weather.raining);info.setThundering(weather.thundering);info.setRainTime(weather.rainTime);info.setThunderTime(weather.thunderTime);info.setClearWeatherTime(weather.clearTime);
  level.setRainLevel(weather.rainLevel);level.setThunderLevel(weather.thunderLevel);
  p.connection.send(new SChangeGameStatePacket(weather.raining?SChangeGameStatePacket.START_RAINING:SChangeGameStatePacket.STOP_RAINING,0));
  p.connection.send(new SChangeGameStatePacket(SChangeGameStatePacket.RAIN_LEVEL_CHANGE,weather.rainLevel));p.connection.send(new SChangeGameStatePacket(SChangeGameStatePacket.THUNDER_LEVEL_CHANGE,weather.thunderLevel));
  // Remove old effects and only persisted attribute modifiers; equipment modifiers remain native.
  p.removeAllEffects();CompoundNBT before=p.saveWithoutId(new CompoundNBT());ListNBT oldAttributes=before.getList("Attributes",10);
  for(int n=0;n<oldAttributes.size();n++){
   CompoundNBT attr=oldAttributes.getCompound(n);net.minecraft.entity.ai.attributes.Attribute type=Registry.ATTRIBUTE.get(new ResourceLocation(attr.getString("Name")));
   if(type==null||p.getAttribute(type)==null)continue;p.getAttribute(type).setBaseValue(type.getDefaultValue());
   ListNBT mods=attr.getList("Modifiers",10);for(int m=0;m<mods.size();m++)if(mods.getCompound(m).hasUUID("UUID"))p.getAttribute(type).removePermanentModifier(mods.getCompound(m).getUUID("UUID"));
  }
  p.setGameMode(net.minecraft.world.GameType.byId(prepared.player.getInt("playerGameType")));
  CompoundNBT merged=p.saveWithoutId(new CompoundNBT());for(String key:PlayerCompatibility.FIELDS){merged.remove(key);if(prepared.player.contains(key))merged.put(key,prepared.player.get(key).copy());}
  p.setRespawnPosition(net.minecraft.world.World.OVERWORLD,null,0,false,false);p.load(merged);p.abilities.loadSaveData(merged);p.onUpdateAbilities();
  p.connection.send(new SUpdateHealthPacket(p.getHealth(),p.getFoodData().getFoodLevel(),p.getFoodData().getSaturationLevel()));
  p.connection.send(new SSetExperiencePacket(p.experienceProgress,p.totalExperience,p.experienceLevel));
  for(EffectInstance effect:p.getActiveEffects())p.connection.send(new SPlayEntityEffectPacket(p.getId(),effect));
  p.connection.send(new SEntityVelocityPacket(p));
  prepared.carry.playerOriginal=prepared.extra.player;prepared.carry.playerApplied=exportPlayer(p).toString();
  StreamingWorld.writeCarry(level,carryPath(server),prepared.carry);
  p.connection.send(new SUpdateTimePacket(s.gameTime,s.dayTime,level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
  ClientHooks.bridge().diagnostics.put("environment",WorldExtras.diagnostics(prepared.carry));
 }
}
