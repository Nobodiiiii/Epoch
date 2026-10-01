package dev.epoch;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.*;

/** Headless mapping/NBT checks: no client, world or display is created. */
public final class LegacyProtocolSmoke {
 public static void main(String[] args)throws Exception{
  Bootstrap.register();
  require(LegacyStates.encode(Blocks.SKULL.getDefaultState()).contains("EpochLegacyVersion"),"Skull sentinel must retain raw legacy state");
  require(LegacyStates.encode(Blocks.PLANKS.getStateFromMeta(5)).contains("dark_oak_planks"),"legacy planks metadata");
  require(LegacyStates.decode("{Name:\"minecraft:chest\",Properties:{facing:\"north\",type:\"single\",waterlogged:\"false\"}}")!=null,"chest default properties");
  require(LegacyStates.decode("{Name:\"minecraft:cherry_planks\"}")==null,"unknown block sentinel");
  NBTTagCompound old=JsonToNBT.getTagFromJson("{id:\"minecraft:planks\",Count:7b,Damage:5s,Slot:2b}");NBTTagCompound canonical=LegacyItems.encode(old);require(canonical.getString("id").equals("minecraft:dark_oak_planks"),"item flattening");require(Handoff.preserves(old,LegacyItems.decode(canonical)),"item metadata round trip");
  old=JsonToNBT.getTagFromJson("{id:\"minecraft:diamond_sword\",Count:1b,Damage:27s,Slot:0b}");canonical=LegacyItems.encode(old);require(canonical.getCompoundTag("tag").getInteger("Damage")==27,"tool damage flattening");require(Handoff.preserves(old,LegacyItems.decode(canonical)),"tool damage round trip");
  boolean rejected=false;try{LegacyItems.decode(JsonToNBT.getTagFromJson("{id:\"minecraft:cherry_planks\",Count:1b,Slot:1b}"));}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"unknown inventory rejected before mutation");
  NBTTagCompound chest=JsonToNBT.getTagFromJson("{id:\"minecraft:chest\",Items:[{id:\"minecraft:dark_oak_planks\",Count:7b,Slot:2b}]}" );require(Handoff.preserves(chest,LegacyItems.encodePayload(LegacyItems.decodePayload(chest))),"container item NBT round trip");
  NBTTagList inventory=inventory("[{id:\"minecraft:diamond\",Count:1b,Slot:0b},{id:\"minecraft:diamond\",Count:1b,Slot:35b},{id:\"minecraft:diamond\",Count:1b,Slot:100b},{id:\"minecraft:diamond\",Count:1b,Slot:103b},{id:\"minecraft:diamond\",Count:1b,Slot:-106b}]");
  require(LegacyItems.decodePlayerInventory(inventory).tagCount()==5,"main, armor and signed-byte offhand slots accepted");
  for(String slot:new String[]{"36b","99b","104b","149b","-105b","-1b","0"})rejectInventory("[{id:\"minecraft:diamond\",Count:1b,Slot:"+slot+"}]","unrepresentable/incorrectly typed slot "+slot);
  rejectInventory("[{id:\"minecraft:diamond\",Count:1b}]","missing slot");
  rejectInventory("[{id:\"minecraft:diamond\",Count:1b,Slot:0b},{id:\"minecraft:stone\",Count:1b,Slot:0b}]","duplicate slot must not overwrite an item");
  rejectInventory("[\"not an item\"]","wrong element type must not silently become an empty inventory");
  require(LegacyItems.decodePlayerInventory(new NBTTagList()).tagCount()==0,"empty inventory accepted");
  String fixture;
  try(java.io.InputStream in=LegacyProtocolSmoke.class.getResourceAsStream("modern-payloads.snbt")){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[2048];for(int n;(n=in.read(buffer))!=-1;)bytes.write(buffer,0,n);fixture=new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}
  NBTTagCompound modern=Snbt.compound(fixture);NBTTagCompound modernChest=modern.getCompoundTag("Chest");
  String customName=modernChest.getTagList("Items",10).getCompoundTagAt(0).getCompoundTag("tag").getCompoundTag("display").getString("Name");
  require(customName.equals("{\"text\":\"Epoch NBT sentinel\"}"),"real 1.20 chest single-quoted JSON custom name");
  String page=modern.getCompoundTag("Bookshelf").getTagList("Items",10).getCompoundTagAt(0).getCompoundTag("tag").getTagList("pages",8).getStringTagAt(0);
  require(page.equals("{\"text\":\"Across versions, this page survives.\"}"),"real written book JSON page");
  require(Handoff.preserves(modernChest,LegacyItems.encodePayload(LegacyItems.decodePayload(modernChest))),"modern single-quoted chest survives item conversion");
  require(Snbt.compound(modern.toString()).equals(modern),"canonical native output remains parseable and lossless");
  String escaped="\"Quoted\" object's C:\\Epoch\\path, literal \\n, newline\n, 中文";
  for(char quote:new char[]{'\'', '"'})require(Snbt.compound("{'quoted key':"+modernQuote(escaped,quote)+"}").getString("quoted key").equals(escaped),"quote/backslash/Unicode values survive "+quote);
  NBTTagCompound arrays=Snbt.compound("{bytes:[B;1B,-2B],ints:[I;1,-2,2147483647],longs:[L;1L,-9223372036854775808L],numericString:'1b',list:['a',\"b\"]}");
  require(arrays.getTag("longs").equals(new NBTTagLongArray(new long[]{1,Long.MIN_VALUE})),"long arrays retain full precision");require(arrays.getTag("numericString").getId()==8,"quoted numeric-looking value stays a string");require(Snbt.compound(arrays.toString()).equals(arrays),"all typed arrays survive canonical serialization");
  rejectSnbt("{bad:'unterminated}");rejectSnbt("{bad:'bad\\q'}");
  System.out.println("Epoch 1.12.2 protocol smoke passed");
 }
 private static String modernQuote(String value,char quote){StringBuilder out=new StringBuilder().append(quote);for(int n=0;n<value.length();n++){char c=value.charAt(n);if(c==quote||c=='\\')out.append('\\');out.append(c);}return out.append(quote).toString();}
 private static void rejectSnbt(String text)throws Exception{boolean rejected=false;try{Snbt.compound(text);}catch(NBTException expected){rejected=true;}require(rejected,"malformed modern SNBT must be rejected");}
 private static NBTTagList inventory(String text)throws Exception{return (NBTTagList)JsonToNBT.getTagFromJson("{Inventory:"+text+"}").getTag("Inventory");}
 private static void rejectInventory(String text,String why)throws Exception{boolean rejected=false;try{LegacyItems.decodePlayerInventory(inventory(text));}catch(IllegalArgumentException expected){rejected=true;}require(rejected,why);}
 private static void require(boolean condition,String description){if(!condition)throw new AssertionError(description);}
}
