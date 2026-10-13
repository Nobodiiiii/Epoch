package dev.epoch;

import com.google.gson.*;
import dev.epoch.bridge.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.*;
import net.minecraft.util.ResourceLocation;

/** Uses Mojang's explicit flattening table rather than version-dependent numeric IDs. */
final class LegacyStates {
 private static final Map<Integer,NBTTagCompound> FORWARD=new HashMap<Integer,NBTTagCompound>();
 private static final Map<String,List<Integer>> REVERSE=new HashMap<String,List<Integer>>();
 static {
  try(Reader reader=new InputStreamReader(LegacyStates.class.getResourceAsStream("/assets/epoch/legacy-blockstates.json"),StandardCharsets.UTF_8)){
   JsonObject root=new JsonParser().parse(reader).getAsJsonObject();
   for(Map.Entry<String,JsonElement> item:root.entrySet()){
    int id=Integer.parseInt(item.getKey());JsonObject object=item.getValue().getAsJsonObject();NBTTagCompound tag=new NBTTagCompound();tag.setString("Name",BlockSemantics.alias(object.get("Name").getAsString()));
    if(object.has("Properties")){NBTTagCompound props=new NBTTagCompound();for(Map.Entry<String,JsonElement> prop:object.getAsJsonObject("Properties").entrySet())props.setString(prop.getKey(),prop.getValue().getAsString());tag.setTag("Properties",props);}
    FORWARD.put(id,tag);REVERSE.computeIfAbsent(tag.getString("Name"),key->new ArrayList<Integer>()).add(id);
   }
  }catch(Exception error){throw new ExceptionInInitializerError(error);}
 }
 static String encode(IBlockState state){
  Block block=state.getBlock();int packed=(Block.getIdFromBlock(block)<<4)|(block.getMetaFromState(state)&15);NBTTagCompound tag=FORWARD.get(packed);
  ResourceLocation registry=block.getRegistryName();
  if(tag!=null&&registry!=null&&"minecraft".equals(registry.getNamespace()))return tag.toString();
  tag=new NBTTagCompound();tag.setString("Name",registry==null?"epoch:unregistered":registry.toString());tag.setString("EpochLegacyVersion",Epoch.VERSION);tag.setInteger("EpochLegacyMeta",block.getMetaFromState(state));return tag.toString();
 }
 @SuppressWarnings({"rawtypes","unchecked"})
 static String encodeActual(IBlockState state)throws IOException {
  SnbtFields tag=SnbtFields.parse(encode(state));if(tag.has("EpochLegacyVersion"))return tag.toString();SnbtFields props=tag.compound("Properties");
  for(Map.Entry<net.minecraft.block.properties.IProperty<?>,Comparable<?>> entry:state.getProperties().entrySet()) {
   net.minecraft.block.properties.IProperty prop=entry.getKey();String key=prop.getName();
   String value=prop.getName((Comparable)entry.getValue());
   // Metadata-to-state names differ from native mounting enums (e.g. lever down_x).
   // Runtime-only piston/bed/wire values are inventoried in nativeState, not silently promoted to portable states.
   String name=tag.get("Name");
   if(key.equals("facing")&&props.has("face"))continue;
   if(name.equals("minecraft:redstone_wire")&&!key.equals("power"))continue;
   if(name.equals("minecraft:piston_head")&&key.equals("short"))continue;
   if(name.endsWith("_bed")&&key.equals("occupied")&&props.get("part").equals("foot"))continue;
   if(props.has(key))props.put(key,value);
  }
  if(!props.empty())tag.raw("Properties",props.toString());return tag.toString();
 }
 private static final Map<String,IBlockState> decoded = new LinkedHashMap<String,IBlockState>(256,.75f,true) {
  protected boolean removeEldestEntry(Map.Entry<String,IBlockState> entry){return size()>4096;}
 };
 static synchronized IBlockState decode(String text)throws Exception{
  if(decoded.containsKey(text))return decoded.get(text);
  IBlockState state=decodeUncached(text);decoded.put(text,state);return state;
 }
 static IBlockState decodeUncached(String text)throws Exception{
  NBTTagCompound tag=Snbt.compound(BlockSemantics.legacy(text));String name=tag.getString("Name");
  if(tag.hasKey("EpochLegacyVersion")){
   if(!Epoch.VERSION.equals(tag.getString("EpochLegacyVersion")))return null;
   ResourceLocation id=new ResourceLocation(name);if(!Block.REGISTRY.containsKey(id))return null;return Block.REGISTRY.getObject(id).getStateFromMeta(tag.getInteger("EpochLegacyMeta"));
  }
  if("minecraft:cave_air".equals(name)||"minecraft:void_air".equals(name))name="minecraft:air";
  List<Integer> options=REVERSE.get(name);if(options==null)return null;NBTTagCompound requested=tag.getCompoundTag("Properties");
  Map<String,String> wanted=new TreeMap<>();for(String k:requested.getKeySet())wanted.put(k,requested.getString(k));
  for(Integer id:options){NBTTagCompound expected=FORWARD.get(id).getCompoundTag("Properties");Map<String,String> reference=new TreeMap<>();for(String k:expected.getKeySet())reference.put(k,expected.getString(k));
   if(BlockSemantics.matches(name,wanted,reference)){Block block=Block.getBlockById(id>>4);if(block!=null)return block.getStateFromMeta(id&15);}
  }return null;
 }

 private static boolean defaultProperty(String key,String value){return ("type".equals(key)&&"single".equals(value))||("waterlogged".equals(key)&&"false".equals(value))||("persistent".equals(key)&&"false".equals(value))||("distance".equals(key)&&"7".equals(value));}
}


