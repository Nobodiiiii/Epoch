package dev.epoch;

import dev.epoch.bridge.*;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.*;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.util.ResourceLocation;

import net.minecraft.potion.*;
import net.minecraft.entity.ai.attributes.*;
import net.minecraft.network.play.server.*;

/** Preflight all environment payloads before Handoff changes blocks. */
final class EnvironmentTransfer {
 static Path carryPath(WorldServer world){return world.getSaveHandler().getWorldDirectory().toPath().resolve("epoch/environment.json.gz");}
 static String biomeName(BiomeGenBase b){
  return b.getClass().getName().startsWith("net.minecraft.world.biome.")?BiomeCompatibility.legacyName(b.biomeID,b.biomeName):"epoch:legacy_biome/"+b.biomeID+"/"+Integer.toHexString((b.getClass().getName()+":"+b.biomeName).hashCode());
 }
 static Map<String,BiomeGenBase> registry(){Map<String,BiomeGenBase> out=new TreeMap<>();for(BiomeGenBase b:BiomeGenBase.getBiomeGenArray())if(b!=null&&b.biomeID<256)out.put(biomeName(b),b);return out;}
 static String[] cells(WorldServer world,Snapshot s){
  String[] out=new String[s.sizeX*s.sizeZ*(s.sizeY/4)];
  for(int z=0;z<s.sizeZ;z++)for(int x=0;x<s.sizeX;x++){
   String id=biomeName(world.getBiomeGenForCoords(s.x0+x,s.z0+z));
   for(int y=0;y<s.sizeY/4;y++)out[(y*s.sizeZ+z)*s.sizeX+x]=id;
  }return out;
 }
 static void capture(MinecraftServer server,WorldServer world,EntityPlayerMP p,Snapshot s)throws Exception{
  WorldExtras out=new WorldExtras();WorldExtras.Carry carry=WorldExtras.readCarry(carryPath(world));String[] current=cells(world,s);
  out.biomes=WorldExtras.Biomes.of(StreamingWorld.restoreBiomes(world,s,current,carry));
  net.minecraft.world.storage.WorldInfo info=world.getWorldInfo();out.weather=new WorldExtras.Weather();
  out.weather.raining=info.isRaining();out.weather.thundering=info.isThundering();out.weather.rainTime=Math.max(0,info.getRainTime());out.weather.thunderTime=Math.max(0,info.getThunderTime());out.weather.clearTime=Math.max(0,(carry==null?0:(int)Math.max(0,Math.min(Integer.MAX_VALUE,carry.clearUntil-world.getTotalWorldTime()))));
  out.weather.rainLevel=world.getRainStrength(1);out.weather.thunderLevel=world.thunderingStrength;
  for(String key:world.getGameRules().getRules())out.gameRules.put(key,world.getGameRules().getGameRuleStringValue(key));
  if(carry!=null)for(Map.Entry<String,String> e:carry.rules.entrySet())if(!out.gameRules.containsKey(e.getKey()))out.gameRules.put(e.getKey(),e.getValue());
  out.player=encode(restorePlayer(exportPlayer(p),carry));s.extras=out;s.schema=2;
 }
 static String encode(NBTTagCompound tag)throws Exception{return LegacyNbt.encode(tag);}
 static NBTTagCompound parse(String text)throws Exception{return LegacyNbt.nativeTag(LegacyNbt.parse(text));}
 @SuppressWarnings("unchecked") static Set<String> keys(NBTTagCompound tag){return tag.func_150296_c();}
 static NBTTagCompound restorePlayer(NBTTagCompound current,WorldExtras.Carry carry)throws Exception{
  if(carry==null||carry.playerOriginal==null||carry.playerApplied==null)return current;
  NBTTagCompound original=parse(carry.playerOriginal),applied=parse(carry.playerApplied);
  Map<String,NBTBase> now=new HashMap<>(),before=new HashMap<>(),after=new HashMap<>();
  for(String key:keys(current))now.put(key,current.getTag(key));
  for(String key:keys(original))before.put(key,original.getTag(key));
  for(String key:keys(applied))after.put(key,applied.getTag(key));
  PlayerCompatibility.restoreMissing(now,before,after);for(Map.Entry<String,NBTBase> e:now.entrySet())current.setTag(e.getKey(),e.getValue().copy());return current;
 }
 static NBTTagCompound exportPlayer(EntityPlayerMP p)throws Exception{
  if(!p.isEntityAlive()||p.ridingEntity!=null||p.isPlayerSleeping())throw new IOException("Finish respawning, dismount and wake up before switching");
  NBTTagCompound raw=new NBTTagCompound();p.writeToNBT(raw);NBTTagCompound out=new NBTTagCompound();
  for(String key:PlayerCompatibility.FIELDS)if(raw.hasKey(key))out.setTag(key,raw.getTag(key).copy());
  out.setInteger("playerGameType",p.theItemInWorldManager.getGameType().getID());
  if(out.hasKey("SpawnX")){if(raw.getInteger("SpawnDimension")!=0)throw new IOException("Respawn dimension outside shared Overworld");out.setString("SpawnDimension","minecraft:overworld");}
  out.setFloat("Health",p.getHealth());
  LegacyNbt.Tag portable=LegacyNbt.from(out);Handoff.convertItems(portable,true);out=LegacyNbt.nativeTag(portable);
  names(out,false);return out;
 }
 static String effectName(Potion effect){int id=effect.id;return effect.getClass().getName().startsWith("net.minecraft.potion.")&&id>0&&id<PlayerCompatibility.LEGACY_EFFECTS.length?"minecraft:"+PlayerCompatibility.LEGACY_EFFECTS[id]:"epoch:legacy_effect/"+id+"/"+Integer.toHexString((effect.getClass().getName()+":"+effect.getName()).hashCode());}
 static void effectNames(NBTTagCompound tag,boolean nativeIds)throws Exception{
  if(nativeIds){
   String id=tag.getString("Id");Potion found=null;for(Potion effect:Potion.potionTypes)if(effect!=null&&effectName(effect).equals(id))found=effect;
   if(found==null)throw new IOException("Effect unavailable: "+id);
   if(tag.getInteger("Duration")<0)throw new IOException("Infinite effect unavailable: "+id);tag.setByte("Id",(byte)found.id);
   for(String key:Arrays.asList("ShowIcon","ShowParticles"))if(tag.hasKey(key)){if(!tag.getBoolean(key))throw new IOException("Hidden effect visuals unavailable");tag.removeTag(key);}
  }else{
   int id=tag.getByte("Id")&255;if(id>=Potion.potionTypes.length||Potion.potionTypes[id]==null)throw new IOException("Unknown native effect");tag.setString("Id",effectName(Potion.potionTypes[id]));
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
  if(tag.hasKey("Motion")){NBTTagList motion=tag.getTagList("Motion",6);if(motion.tagCount()!=3)throw new IOException("Motion must contain three doubles");for(int n=0;n<3;n++)if(!Double.isFinite(motion.func_150309_d(n))||Math.abs(motion.func_150309_d(n))>10)throw new IOException("Motion exceeds native range");}
  if(tag.getDouble("foodLevel")<0||tag.getDouble("foodLevel")>20||tag.getDouble("foodSaturationLevel")<0||tag.getDouble("foodExhaustionLevel")<0||tag.getDouble("AbsorptionAmount")<0||tag.getDouble("FallDistance")<0)throw new IOException("Invalid hunger/absorption/fall state");
 }
 static NBTTagCompound preparePlayer(EntityPlayerMP p,String text)throws Exception{
  NBTTagCompound canonical=parse(text);for(String key:keys(canonical))if(!PlayerCompatibility.FIELDS.contains(key))throw new IOException("Unsupported player field: "+key);
  validateScalars(canonical);
  float health=canonical.getFloat("Health"),xp=canonical.getFloat("XpP");
  if(!Float.isFinite(health)||health<=0||!Float.isFinite(xp)||xp<0||xp>1||canonical.getInteger("XpLevel")<0||canonical.getInteger("XpTotal")<0)throw new IOException("Invalid health/experience");
  int mode=canonical.getInteger("playerGameType");if(mode<0||mode>2)throw new IOException("Invalid game mode");
  if(canonical.hasKey("SpawnDimension")&&!canonical.getString("SpawnDimension").equals("minecraft:overworld"))throw new IOException("Respawn dimension outside shared Overworld");
  NBTTagList ender=canonical.getTagList("EnderItems",10);Set<Integer> slots=new HashSet<>();for(int n=0;n<ender.tagCount();n++){NBTTagCompound stack=ender.getCompoundTagAt(n);int slot=stack.getByte("Slot")&255;if(!stack.hasKey("Slot",1)||slot>=27||!slots.add(slot))throw new IOException("Invalid ender chest slot");}
  NBTTagCompound nativeTag=(NBTTagCompound)canonical.copy();
  nativeTag.setTag("EnderItems",ItemTransfer.decodeList(ender));
  names(nativeTag,true);nativeTag.setFloat("HealF",nativeTag.getFloat("Health"));nativeTag.removeTag("XpSeed");
  if(nativeTag.hasKey("SpawnDimension"))nativeTag.setInteger("SpawnDimension",0);
  nativeTag.removeTag("SpawnAngle");
  NBTTagList effects=nativeTag.getTagList("ActiveEffects",10);
  for(int n=0;n<effects.tagCount();n++){NBTTagCompound expected=effects.getCompoundTagAt(n);PotionEffect effect=PotionEffect.readCustomPotionEffectFromNBT((NBTTagCompound)expected.copy());if(effect==null||!preservesEffect(expected,effect))throw new IOException("Effect payload cannot roundtrip");}
  NBTTagList attrs=nativeTag.getTagList("Attributes",10),accepted=new NBTTagList();Set<String> seen=new HashSet<>();
  for(int n=0;n<attrs.tagCount();n++){
   NBTTagCompound attr=attrs.getCompoundTagAt(n);for(String key:keys(attr))if(!Arrays.asList("Name","Base","Modifiers").contains(key))throw new IOException("Unsupported attribute field: "+key);String name=attr.getString("Name");IAttributeInstance target=p.getAttributeMap().getAttributeInstanceByName(name);
   if(!seen.add(name)||!Double.isFinite(attr.getDouble("Base")))throw new IOException("Invalid attribute");
   if(target==null){if(PlayerCompatibility.absentDefault(PlayerCompatibility.canonicalAttribute(name),attr.getDouble("Base"),attr.getTagList("Modifiers",10).tagCount()!=0))continue;throw new IOException("Player attribute unavailable: "+name);}
   NBTTagList mods=attr.getTagList("Modifiers",10);Set<UUID> ids=new HashSet<>();for(int m=0;m<mods.tagCount();m++){NBTTagCompound mod=mods.getCompoundTagAt(m);AttributeModifier decoded=net.minecraft.entity.SharedMonsterAttributes.readAttributeModifierFromNBT(mod);if(decoded==null||!ids.add(decoded.getID())||!Double.isFinite(decoded.getAmount())||!preservesModifier(mod,decoded))throw new IOException("Invalid attribute modifier");}
   accepted.appendTag(attr);
  }nativeTag.setTag("Attributes",accepted);return nativeTag;
 }
 static boolean preservesEffect(NBTTagCompound expected,PotionEffect effect)throws Exception{NBTTagCompound actual=new NBTTagCompound();effect.writeCustomPotionEffectToNBT(actual);return LegacyNbt.preserves(LegacyNbt.from(expected),LegacyNbt.from(actual));}
 static NBTTagCompound modifierTag(AttributeModifier m){NBTTagCompound t=new NBTTagCompound();t.setString("Name",m.getName());t.setDouble("Amount",m.getAmount());t.setInteger("Operation",m.getOperation());t.setLong("UUIDMost",m.getID().getMostSignificantBits());t.setLong("UUIDLeast",m.getID().getLeastSignificantBits());return t;}
 static boolean preservesModifier(NBTTagCompound expected,AttributeModifier modifier)throws Exception{return LegacyNbt.preserves(LegacyNbt.from(expected),LegacyNbt.from(modifierTag(modifier)));}
 static final class Prepared {
  final WorldExtras extra;final NBTTagCompound player;final Map<String,String> rules;final String[] applied;final Map<String,BiomeGenBase> biomes;final WorldExtras.Carry carry;
  Prepared(WorldExtras e,NBTTagCompound p,Map<String,String> r,String[] a,Map<String,BiomeGenBase> b,WorldExtras.Carry c){extra=e;player=p;rules=r;applied=a;biomes=b;carry=c;}
 }
 static Prepared prepare(MinecraftServer server,WorldServer world,EntityPlayerMP p,Snapshot s)throws Exception{
  s.validate();if(s.extras==null)return null;Map<String,BiomeGenBase> biomes=registry();String[] original=s.extras.biomes.expand(s.sizeX*s.sizeZ*(s.sizeY/4));
  String[] applied=BiomeCompatibility.project(original,s.sizeX,s.sizeY/4,s.sizeZ,false,biomes.keySet());
  WorldExtras.Carry carry=new WorldExtras.Carry();carry.bounds(s);carry.original=s.extras.biomes;carry.applied=WorldExtras.Biomes.of(applied);
  Set<String> known=new HashSet<>(Arrays.asList(world.getGameRules().getRules()));Map<String,String> rules=new TreeMap<>();
  for(Map.Entry<String,String> e:s.extras.gameRules.entrySet())if(known.contains(e.getKey())){WorldExtras.validateRule(e.getKey(),e.getValue(),world.getGameRules().getGameRuleStringValue(e.getKey()));rules.put(e.getKey(),e.getValue());}else carry.rules.put(e.getKey(),e.getValue());
  return new Prepared(s.extras,preparePlayer(p,s.extras.player),rules,applied,biomes,carry);
 }
 static void apply(MinecraftServer server,WorldServer world,EntityPlayerMP p,Snapshot s,Prepared prepared)throws Exception{
  if(prepared==null){Files.deleteIfExists(carryPath(world));return;}
  for(int z=0;z<s.sizeZ;z++)for(int x=0;x<s.sizeX;x++){
   int wx=s.x0+x,wz=s.z0+z;net.minecraft.world.chunk.Chunk chunk=world.getChunkFromChunkCoords(wx>>4,wz>>4);
   chunk.getBiomeArray()[(wz&15)*16+(wx&15)]=(byte)prepared.biomes.get(prepared.applied[z*s.sizeX+x]).biomeID;chunk.setChunkModified();
  }
  for(Map.Entry<String,String> e:prepared.rules.entrySet())world.getGameRules().setOrCreateGameRule(e.getKey(),e.getValue());
  net.minecraft.world.storage.WorldInfo info=world.getWorldInfo();WorldExtras.Weather w=prepared.extra.weather;
  info.setRaining(w.raining);info.setThundering(w.thundering);info.setRainTime(w.rainTime);info.setThunderTime(w.thunderTime);if(w.clearTime>0){info.setRaining(false);info.setThundering(false);info.setRainTime(Math.max(w.rainTime,w.clearTime));info.setThunderTime(Math.max(w.thunderTime,w.clearTime));}prepared.carry.clearUntil=world.getTotalWorldTime()+w.clearTime;
  world.setRainStrength(w.rainLevel);world.setThunderStrength(w.thunderLevel);
  p.playerNetServerHandler.sendPacket(new S2BPacketChangeGameState(info.isRaining()?2:1,0));p.playerNetServerHandler.sendPacket(new S2BPacketChangeGameState(7,w.rainLevel));p.playerNetServerHandler.sendPacket(new S2BPacketChangeGameState(8,w.thunderLevel));
  p.clearActivePotions();for(Object value:p.getAttributeMap().getAllAttributes()){IAttributeInstance attr=(IAttributeInstance)value;attr.setBaseValue(attr.getAttribute().getDefaultValue());for(Object m:new ArrayList(attr.func_111122_c())){AttributeModifier mod=(AttributeModifier)m;if(mod.isSaved())attr.removeModifier(mod);}}
  p.setGameType(WorldSettings.GameType.getByID(prepared.player.getInteger("playerGameType")));
  NBTTagCompound merged=new NBTTagCompound();p.writeToNBT(merged);for(String key:PlayerCompatibility.FIELDS){merged.removeTag(key);if(prepared.player.hasKey(key))merged.setTag(key,prepared.player.getTag(key).copy());}
  merged.setFloat("HealF",prepared.player.getFloat("Health"));p.setSpawnChunk(null,false,0);p.readFromNBT(merged);p.capabilities.readCapabilitiesFromNBT(merged);p.sendPlayerAbilities();
  p.playerNetServerHandler.sendPacket(new S06PacketUpdateHealth(p.getHealth(),p.getFoodStats().getFoodLevel(),p.getFoodStats().getSaturationLevel()));p.playerNetServerHandler.sendPacket(new S1FPacketSetExperience(p.experience,p.experienceTotal,p.experienceLevel));
  for(Object value:p.getActivePotionEffects())p.playerNetServerHandler.sendPacket(new S1DPacketEntityEffect(p.getEntityId(),(PotionEffect)value));
  p.playerNetServerHandler.sendPacket(new S12PacketEntityVelocity(p));
  prepared.carry.playerOriginal=prepared.extra.player;prepared.carry.playerApplied=encode(exportPlayer(p));StreamingWorld.writeCarry(world,carryPath(world),prepared.carry);
  p.playerNetServerHandler.sendPacket(new S03PacketTimeUpdate(s.gameTime,s.dayTime,world.getGameRules().getGameRuleBooleanValue("doDaylightCycle")));
  ClientHooks.bridge().diagnostics.put("environment",WorldExtras.diagnostics(prepared.carry));
 }
}
