package dev.epoch;

import com.google.gson.*;
import dev.epoch.bridge.*;
import java.io.*;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

final class LegacyStates {
 static final class State {final Block block;final int meta;State(Block b,int m){block=b;meta=m;}}
 private static final Map<Integer,String> encoded=new HashMap<Integer,String>();private static final Map<String,State> reverse=new HashMap<String,State>();private static boolean loaded;
 private static void load()throws IOException{if(loaded)return;InputStream stream=LegacyStates.class.getResourceAsStream("/assets/epoch/legacy-blockstates.json");if(stream==null)throw new IOException("Missing legacy blockstate mapping");try(Reader reader=new InputStreamReader(stream,"UTF-8")){JsonObject entries=new JsonParser().parse(reader).getAsJsonObject();for(Map.Entry<String,JsonElement> e:entries.entrySet()){int numeric=Integer.parseInt(e.getKey()),id=numeric>>4,meta=numeric&15;Block b=Block.getBlockById(id);if(b==null||(b==Blocks.air&&id!=0)||!String.valueOf(Block.blockRegistry.getNameForObject(b)).startsWith("minecraft:")||!supportedMeta(id,meta))continue;LegacyNbt.Tag state=fromJson(e.getValue().getAsJsonObject());String snbt=BlockSemantics.normalize(LegacyNbt.encode(state));state=LegacyNbt.parse(snbt);encoded.put(numeric,snbt);reverse.put(key(state),new State(b,meta));}}loaded=true;}
 private static boolean supportedMeta(int id,int meta){if(id==1||id==19)return meta==0;return true;}
 private static LegacyNbt.Tag fromJson(JsonObject obj){LegacyNbt.Tag state=LegacyNbt.compound();for(Map.Entry<String,JsonElement> e:obj.entrySet()){if(e.getValue().isJsonObject())LegacyNbt.map(state).put(e.getKey(),fromJson(e.getValue().getAsJsonObject()));else LegacyNbt.map(state).put(e.getKey(),new LegacyNbt.Tag(8,e.getValue().getAsString()));}return state;}
 private static String key(LegacyNbt.Tag tag){String name=LegacyNbt.string(LegacyNbt.map(tag).get("Name"));LegacyNbt.Tag props=LegacyNbt.map(tag).get("Properties");SortedMap<String,String> sorted=new TreeMap<String,String>();if(props!=null)for(Map.Entry<String,LegacyNbt.Tag> e:LegacyNbt.map(props).entrySet()){String value=LegacyNbt.string(e.getValue());if(e.getKey().equals("waterlogged")&&value.equals("false"))continue;sorted.put(e.getKey(),value);}return name+sorted;}
 static String encode(Block block,int meta)throws IOException{load();String result=String.valueOf(Block.blockRegistry.getNameForObject(block)).startsWith("minecraft:")?encoded.get(Block.getIdFromBlock(block)*16+meta):null;if(result!=null)return result;LegacyNbt.Tag state=LegacyNbt.compound();LegacyNbt.map(state).put("Name",new LegacyNbt.Tag(8,String.valueOf(Block.blockRegistry.getNameForObject(block))));LegacyNbt.map(state).put("EpochLegacyVersion",new LegacyNbt.Tag(8,Epoch.VERSION));LegacyNbt.map(state).put("EpochLegacyMeta",new LegacyNbt.Tag(3,meta));return LegacyNbt.encode(state);}
 private static final Map<String,State> decoded = new LinkedHashMap<String,State>(256,.75f,true) {
  protected boolean removeEldestEntry(Map.Entry<String,State> entry){return size()>4096;}
 };
 static synchronized State decode(String text)throws IOException{
  if(decoded.containsKey(text))return decoded.get(text);
  State state=decodeUncached(text);decoded.put(text,state);return state;
 }
 static State decodeUncached(String text)throws IOException{load();LegacyNbt.Tag parsed=LegacyNbt.parse(BlockSemantics.legacy(text));Map<String,LegacyNbt.Tag> data=LegacyNbt.map(parsed);if(data.containsKey("EpochLegacyVersion")){if(!Epoch.VERSION.equals(LegacyNbt.string(data.get("EpochLegacyVersion"))))return null;String name=LegacyNbt.string(data.get("Name"));Object b=Block.blockRegistry.getObject(name);int meta=Integer.parseInt(LegacyNbt.string(data.get("EpochLegacyMeta")));if(b instanceof Block&&Block.blockRegistry.containsKey(name)&&meta>=0&&meta<16)return new State((Block)b,meta);return null;}State exact=reverse.get(key(parsed));if(exact!=null)return exact;
 String name=LegacyNbt.string(data.get("Name"));SnbtFields request=SnbtFields.parse(LegacyNbt.encode(parsed));Map<String,String> props=BlockSemantics.properties(request);
 for(Map.Entry<Integer,String> entry:encoded.entrySet()){SnbtFields expected=SnbtFields.parse(entry.getValue());if(name.equals(expected.get("Name"))&&BlockSemantics.matches(name,props,BlockSemantics.properties(expected)))return new State(Block.getBlockById(entry.getKey()>>4),entry.getKey()&15);}return null;}
 static State decodeItem(String name)throws IOException{load();for(Map.Entry<Integer,String> e:encoded.entrySet()){LegacyNbt.Tag tag=LegacyNbt.parse(e.getValue());if(name.equals(LegacyNbt.string(LegacyNbt.map(tag).get("Name"))))return new State(Block.getBlockById(e.getKey()>>4),e.getKey()&15);}return null;}
}

