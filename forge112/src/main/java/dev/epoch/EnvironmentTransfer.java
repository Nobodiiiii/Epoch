package dev.epoch;

import dev.epoch.bridge.*;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.*;
import net.minecraft.world.biome.Biome;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.potion.*;
import net.minecraft.entity.ai.attributes.*;
import net.minecraft.network.play.server.*;

/** Preflight all environment payloads before Handoff changes blocks. */
final class EnvironmentTransfer {
 static Path carryPath(WorldServer world){return world.getSaveHandler().getWorldDirectory().toPath().resolve("epoch/environment.json.gz");}
 static Map<String,Biome> registry(){Map<String,Biome> out=new TreeMap<>();for(ResourceLocation id:Biome.REGISTRY.getKeys()){Biome b=Biome.REGISTRY.getObject(id);if(Biome.getIdForBiome(b)>=0&&Biome.getIdForBiome(b)<256)out.put(id.toString(),b);}return out;}
 static String[] cells(WorldServer world,Snapshot s){
  String[] out=new String[s.sizeX*s.sizeZ*(s.sizeY/4)];
  for(int z=0;z<s.sizeZ;z++)for(int x=0;x<s.sizeX;x++){
   String id=Biome.REGISTRY.getNameForObject(world.getBiome(new BlockPos(s.x0+x,64,s.z0+z))).toString();
   for(int y=0;y<s.sizeY/4;y++)out[(y*s.sizeZ+z)*s.sizeX+x]=id;
  }return out;
 }
 static java.lang.reflect.Field thunderField;
 static java.lang.reflect.Field thunderStorage()throws Exception{
  if(thunderField==null){for(String name:new String[]{"thunderingStrength","field_73017_q"})try{thunderField=net.minecraft.world.World.class.getDeclaredField(name);thunderField.setAccessible(true);break;}catch(NoSuchFieldException ignored){}
   if(thunderField==null)throw new IOException("Missing native thunder field");}
  return thunderField;
 }
 static float rawThunder(net.minecraft.world.World level)throws Exception{return thunderStorage().getFloat(level);}
 static void capture(MinecraftServer server,WorldServer world,EntityPlayerMP p,Snapshot s)throws Exception{
  WorldExtras out=new WorldExtras();WorldExtras.Carry carry=WorldExtras.readCarry(carryPath(world));String[] current=cells(world,s);
  out.biomes=WorldExtras.Biomes.of(StreamingWorld.restoreBiomes(world,s,current,carry));
  net.minecraft.world.storage.WorldInfo info=world.getWorldInfo();out.weather=new WorldExtras.Weather();
  out.weather.raining=info.isRaining();out.weather.thundering=info.isThundering();out.weather.rainTime=Math.max(0,info.getRainTime());out.weather.thunderTime=Math.max(0,info.getThunderTime());out.weather.clearTime=Math.max(0,info.getCleanWeatherTime());
  out.weather.rainLevel=world.getRainStrength(1);out.weather.thunderLevel=rawThunder(world);
  for(String key:world.getGameRules().getRules())out.gameRules.put(key,world.getGameRules().getString(key));
  if(carry!=null)for(Map.Entry<String,String> e:carry.rules.entrySet())if(!out.gameRules.containsKey(e.getKey()))out.gameRules.put(e.getKey(),e.getValue());
  out.player=encode(restorePlayer(exportPlayer(p),carry));s.extras=out;s.schema=2;
 }
 static String encode(NBTTagCompound tag)throws Exception{return tag.toString();}
 static NBTTagCompound parse(String text)throws Exception{return Snbt.compound(text);}
 static NBTTagCompound restorePlayer(NBTTagCompound current,WorldExtras.Carry carry)throws Exception{
  if(carry==null||carry.playerOriginal==null||carry.playerApplied==null)return current;
  NBTTagCompound original=parse(carry.playerOriginal),applied=parse(carry.playerApplied);
  Map<String,NBTBase> now=new HashMap<>(),before=new HashMap<>(),after=new HashMap<>();
  for(String key:current.getKeySet())now.put(key,current.getTag(key));
  for(String key:original.getKeySet())before.put(key,original.getTag(key));
  for(String key:applied.getKeySet())after.put(key,applied.getTag(key));
  PlayerCompatibility.restoreMissing(now,before,after);for(Map.Entry<String,NBTBase> e:now.entrySet())current.setTag(e.getKey(),e.getValue().copy());return current;
 }
 static NBTTagCompound exportPlayer(EntityPlayerMP p)throws Exception{
  if(!p.isEntityAlive()||p.isRiding()||p.isPlayerSleeping())throw new IOException("Finish respawning, dismount and wake up before switching");
  NBTTagCompound raw=new NBTTagCompound();p.writeToNBT(raw);NBTTagCompound out=new NBTTagCompound();
  for(String key:PlayerCompatibility.FIELDS)if(raw.hasKey(key))out.setTag(key,raw.getTag(key).copy());
  out.setInteger("playerGameType",p.interactionManager.getGameType().getID());
  if(out.hasKey("SpawnX")){if(raw.getInteger("SpawnDimension")!=0)throw new IOException("Respawn dimension outside shared Overworld");out.setString("SpawnDimension","minecraft:overworld");}
  out.setTag("EnderItems",LegacyItems.encodeList(raw.getTagList("EnderItems",10)));
  names(out,false);return out;
 }
 static void effectNames(NBTTagCompound tag,boolean nativeIds)throws Exception{
  if(nativeIds){
   ResourceLocation id=new ResourceLocation(tag.getString("Id"));Potion effect=Potion.REGISTRY.getObject(id);
   if(effect==null||!Potion.REGISTRY.containsKey(id))throw new IOException("Effect unavailable: "+id);
   if(tag.getInteger("Duration")<0)throw new IOException("Infinite effect unavailable: "+id);
   int numeric=Potion.getIdFromPotion(effect);if(numeric>255)throw new IOException("Effect ID exceeds byte format");tag.setByte("Id",(byte)numeric);
   if(tag.hasKey("ShowIcon")){if(!tag.getBoolean("ShowIcon"))throw new IOException("Hidden effect icon unavailable");tag.removeTag("ShowIcon");}
  }else{
   PotionEffect effect=PotionEffect.readCustomPotionEffectFromNBT(tag.copy());if(effect==null)throw new IOException("Unknown native effect");
   tag.setString("Id",Potion.REGISTRY.getNameForObject(effect.getPotion()).toString());
  }
  if(tag.hasKey("HiddenEffect"))throw new IOException("Hidden effect chains unavailable in this version");
 }
 static void names(NBTTagCompound tag,boolean nativeIds)throws Exception{
  NBTTagList effects=tag.getTagList("ActiveEffects",10);for(int n=0;n<effects.tagCount();n++)effectNames(effects.getCompoundTagAt(n),nativeIds);
  NBTTagList attrs=tag.getTagList("Attributes",10);for(int n=0;n<attrs.tagCount();n++){NBTTagCompound a=attrs.getCompoundTagAt(n);a.setString("Name",nativeIds?PlayerCompatibility.legacyAttribute(a.getString("Name")):PlayerCompatibility.canonicalAttribute(a.getString("Name")));}
 }
 static void validateScalars(NBTTagCompound tag)throws IOException{
  for(String key:Arrays.asList("Health","AbsorptionAmount","Air","Fire","FallDistance","foodLevel","foodTickTimer","foodExhaustionLevel","foodSaturationLevel","XpLevel","XpP","XpTotal","XpSeed","Score","SpawnX","SpawnY","SpawnZ","SpawnAngle","HurtTime","DeathTime","PortalCooldown","playerGameType"))
   if(tag.hasKey(key)&&(!tag.hasKey(key,99)||!Double.isFinite(tag.getDouble(key))))throw new IOException("Invalid numeric player field: "+key);
  for(String key:Arrays.asList("ActiveEffects","Attributes","EnderItems","Motion"))if(tag.hasKey(key)&&!tag.hasKey(key,9))throw new IOException("Invalid player list: "+key);
  for(String key:Arrays.asList("ActiveEffects","Attributes","EnderItems"))if(tag.hasKey(key)&&((NBTTagList)tag.getTag(key)).tagCount()!=tag.getTagList(key,10).tagCount())throw new IOException("Player list entries must be compounds: "+key);
  if(tag.hasKey("abilities")&&!tag.hasKey("abilities",10))throw new IOException("Invalid abilities");
  NBTTagCompound abilities=tag.getCompoundTag("abilities");for(String key:Arrays.asList("walkSpeed","flySpeed"))if(abilities.hasKey(key)&&(!abilities.hasKey(key,99)||!Double.isFinite(abilities.getDouble(key))||abilities.getDouble(key)<0))throw new IOException("Invalid ability speed");
  if(tag.hasKey("Motion")){NBTTagList motion=tag.getTagList("Motion",6);if(motion.tagCount()!=3)throw new IOException("Motion must contain three doubles");for(int n=0;n<3;n++)if(!Double.isFinite(motion.getDoubleAt(n))||Math.abs(motion.getDoubleAt(n))>10)throw new IOException("Motion exceeds native range");}
  if(tag.getDouble("foodLevel")<0||tag.getDouble("foodLevel")>20||tag.getDouble("foodSaturationLevel")<0||tag.getDouble("foodExhaustionLevel")<0||tag.getDouble("AbsorptionAmount")<0||tag.getDouble("FallDistance")<0)throw new IOException("Invalid hunger/absorption/fall state");
 }
 static NBTTagCompound preparePlayer(EntityPlayerMP p,String text)throws Exception{
  NBTTagCompound canonical=parse(text);for(String key:canonical.getKeySet())if(!PlayerCompatibility.FIELDS.contains(key))throw new IOException("Unsupported player field: "+key);
  validateScalars(canonical);
  float health=canonical.getFloat("Health"),xp=canonical.getFloat("XpP");
  if(!Float.isFinite(health)||health<=0||!Float.isFinite(xp)||xp<0||xp>1||canonical.getInteger("XpLevel")<0||canonical.getInteger("XpTotal")<0)throw new IOException("Invalid health/experience");
  int mode=canonical.getInteger("playerGameType");if(mode<0||mode>3)throw new IOException("Invalid game mode");
  if(canonical.hasKey("SpawnDimension")&&!canonical.getString("SpawnDimension").equals("minecraft:overworld"))throw new IOException("Respawn dimension outside shared Overworld");
  NBTTagList ender=canonical.getTagList("EnderItems",10);Set<Integer> slots=new HashSet<>();for(int n=0;n<ender.tagCount();n++){NBTTagCompound stack=ender.getCompoundTagAt(n);int slot=stack.getByte("Slot")&255;if(!stack.hasKey("Slot",1)||slot>=27||!slots.add(slot))throw new IOException("Invalid ender chest slot");}
  NBTTagCompound nativeTag=canonical.copy();nativeTag.setTag("EnderItems",ItemTransfer.decodeList(ender));names(nativeTag,true);
  if(nativeTag.hasKey("SpawnDimension"))nativeTag.setInteger("SpawnDimension",0);
  nativeTag.removeTag("SpawnAngle");
  NBTTagList effects=nativeTag.getTagList("ActiveEffects",10);
  for(int n=0;n<effects.tagCount();n++){NBTTagCompound expected=effects.getCompoundTagAt(n);PotionEffect effect=PotionEffect.readCustomPotionEffectFromNBT(expected.copy());if(effect==null||!Handoff.preserves(expected,effect.writeCustomPotionEffectToNBT(new NBTTagCompound())))throw new IOException("Effect payload cannot roundtrip");}
  NBTTagList attrs=nativeTag.getTagList("Attributes",10),accepted=new NBTTagList();Set<String> seen=new HashSet<>();
  for(int n=0;n<attrs.tagCount();n++){
   NBTTagCompound attr=attrs.getCompoundTagAt(n);for(String key:attr.getKeySet())if(!Arrays.asList("Name","Base","Modifiers").contains(key))throw new IOException("Unsupported attribute field: "+key);String name=attr.getString("Name");IAttributeInstance target=p.getAttributeMap().getAttributeInstanceByName(name);
   if(!seen.add(name)||!Double.isFinite(attr.getDouble("Base")))throw new IOException("Invalid attribute");
   if(target==null){if(PlayerCompatibility.absentDefault(PlayerCompatibility.canonicalAttribute(name),attr.getDouble("Base"),attr.getTagList("Modifiers",10).tagCount()!=0))continue;throw new IOException("Player attribute unavailable: "+name);}
   NBTTagList mods=attr.getTagList("Modifiers",10);Set<UUID> ids=new HashSet<>();for(int m=0;m<mods.tagCount();m++){NBTTagCompound mod=mods.getCompoundTagAt(m);AttributeModifier decoded=net.minecraft.entity.SharedMonsterAttributes.readAttributeModifierFromNBT(mod);if(decoded==null||!ids.add(decoded.getID())||!Double.isFinite(decoded.getAmount())||!Handoff.preserves(mod,net.minecraft.entity.SharedMonsterAttributes.writeAttributeModifierToNBT(decoded)))throw new IOException("Invalid attribute modifier");}
   accepted.appendTag(attr);
  }nativeTag.setTag("Attributes",accepted);return nativeTag;
 }
 static final class Prepared {
  final WorldExtras extra;final NBTTagCompound player;final Map<String,String> rules;final String[] applied;final Map<String,Biome> biomes;final WorldExtras.Carry carry;
  Prepared(WorldExtras e,NBTTagCompound p,Map<String,String> r,String[] a,Map<String,Biome> b,WorldExtras.Carry c){extra=e;player=p;rules=r;applied=a;biomes=b;carry=c;}
 }
 static Prepared prepare(MinecraftServer server,WorldServer world,EntityPlayerMP p,Snapshot s)throws Exception{
  s.validate();if(s.extras==null)return null;Map<String,Biome> biomes=registry();String[] original=s.extras.biomes.expand(s.sizeX*s.sizeZ*(s.sizeY/4));
  String[] applied=BiomeCompatibility.project(original,s.sizeX,s.sizeY/4,s.sizeZ,false,biomes.keySet());
  WorldExtras.Carry carry=new WorldExtras.Carry();carry.bounds(s);carry.original=s.extras.biomes;carry.applied=WorldExtras.Biomes.of(applied);
  Set<String> known=new HashSet<>(Arrays.asList(world.getGameRules().getRules()));Map<String,String> rules=new TreeMap<>();
  for(Map.Entry<String,String> e:s.extras.gameRules.entrySet())if(known.contains(e.getKey())){if(world.getGameRules().areSameType(e.getKey(),GameRules.ValueType.BOOLEAN_VALUE)||world.getGameRules().areSameType(e.getKey(),GameRules.ValueType.NUMERICAL_VALUE))WorldExtras.validateRule(e.getKey(),e.getValue(),world.getGameRules().getString(e.getKey()));rules.put(e.getKey(),e.getValue());}else carry.rules.put(e.getKey(),e.getValue());
  return new Prepared(s.extras,preparePlayer(p,s.extras.player),rules,applied,biomes,carry);
 }
 static void apply(MinecraftServer server,WorldServer world,EntityPlayerMP p,Snapshot s,Prepared prepared)throws Exception{
  if(prepared==null){Files.deleteIfExists(carryPath(world));return;}
  for(int z=0;z<s.sizeZ;z++)for(int x=0;x<s.sizeX;x++){
   int wx=s.x0+x,wz=s.z0+z;net.minecraft.world.chunk.Chunk chunk=world.getChunk(wx>>4,wz>>4);
   chunk.getBiomeArray()[(wz&15)*16+(wx&15)]=(byte)Biome.getIdForBiome(prepared.biomes.get(prepared.applied[z*s.sizeX+x]));chunk.markDirty();
  }
  for(Map.Entry<String,String> e:prepared.rules.entrySet())world.getGameRules().setOrCreateGameRule(e.getKey(),e.getValue());
  net.minecraft.world.storage.WorldInfo info=world.getWorldInfo();WorldExtras.Weather w=prepared.extra.weather;
  info.setRaining(w.raining);info.setThundering(w.thundering);info.setRainTime(w.rainTime);info.setThunderTime(w.thunderTime);info.setCleanWeatherTime(w.clearTime);
  world.setRainStrength(w.rainLevel);world.setThunderStrength(w.thunderLevel);
  p.connection.sendPacket(new SPacketChangeGameState(w.raining?2:1,0));p.connection.sendPacket(new SPacketChangeGameState(7,w.rainLevel));p.connection.sendPacket(new SPacketChangeGameState(8,w.thunderLevel));
  p.clearActivePotions();for(IAttributeInstance attr:p.getAttributeMap().getAllAttributes())attr.setBaseValue(attr.getAttribute().getDefaultValue());for(IAttributeInstance attr:p.getAttributeMap().getAllAttributes())for(AttributeModifier mod:new ArrayList<AttributeModifier>(attr.getModifiers()))if(mod.isSaved())attr.removeModifier(mod);
  p.setGameType(GameType.getByID(prepared.player.getInteger("playerGameType")));
  NBTTagCompound merged=new NBTTagCompound();p.writeToNBT(merged);for(String key:PlayerCompatibility.FIELDS){merged.removeTag(key);if(prepared.player.hasKey(key))merged.setTag(key,prepared.player.getTag(key).copy());}
  p.setSpawnPoint(null,false);p.readFromNBT(merged);p.capabilities.readCapabilitiesFromNBT(merged);p.sendPlayerAbilities();
  p.connection.sendPacket(new SPacketUpdateHealth(p.getHealth(),p.getFoodStats().getFoodLevel(),p.getFoodStats().getSaturationLevel()));p.connection.sendPacket(new SPacketSetExperience(p.experience,p.experienceTotal,p.experienceLevel));
  for(PotionEffect effect:p.getActivePotionEffects())p.connection.sendPacket(new SPacketEntityEffect(p.getEntityId(),effect));
  p.connection.sendPacket(new SPacketEntityVelocity(p));
  prepared.carry.playerOriginal=prepared.extra.player;prepared.carry.playerApplied=encode(exportPlayer(p));WorldExtras.writeCarry(carryPath(world),prepared.carry);
  p.connection.sendPacket(new SPacketTimeUpdate(s.gameTime,s.dayTime,world.getGameRules().getBoolean("doDaylightCycle")));
  ClientHooks.bridge().diagnostics.put("environment",WorldExtras.diagnostics(prepared.carry));
 }
}
