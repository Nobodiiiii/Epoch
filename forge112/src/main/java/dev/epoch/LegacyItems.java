package dev.epoch;

import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.util.ResourceLocation;

/** Converts explicitly representable item names and damage without discarding opaque tags. */
final class LegacyItems {
 private static final Map<String,String> RENAMES=new HashMap<String,String>();
 private static final Map<String,NBTTagCompound> REVERSE=new HashMap<String,NBTTagCompound>();
 private static boolean initialized;
 static {String[][] pairs={{"sign","oak_sign"},{"boat","oak_boat"},{"reeds","sugar_cane"},{"melon","melon_slice"},{"speckled_melon","glistering_melon_slice"},{"chorus_fruit_popped","popped_chorus_fruit"},{"netherbrick","nether_brick"},{"fireworks","firework_rocket"},{"firework_charge","firework_star"},{"record_13","music_disc_13"},{"record_cat","music_disc_cat"},{"record_blocks","music_disc_blocks"},{"record_chirp","music_disc_chirp"},{"record_far","music_disc_far"},{"record_mall","music_disc_mall"},{"record_mellohi","music_disc_mellohi"},{"record_stal","music_disc_stal"},{"record_strad","music_disc_strad"},{"record_ward","music_disc_ward"},{"record_11","music_disc_11"},{"record_wait","music_disc_wait"}};for(String[] pair:pairs)RENAMES.put("minecraft:"+pair[0],"minecraft:"+pair[1]);}
 static NBTTagList encodeList(NBTTagList source){NBTTagList out=new NBTTagList();for(int n=0;n<source.tagCount();n++)out.appendTag(encode(source.getCompoundTagAt(n)));return out;}
 static NBTTagCompound encode(NBTTagCompound source){NBTTagCompound out=source.copy();String name=source.getString("id");ResourceLocation id=new ResourceLocation(name);if(!Item.REGISTRY.containsKey(id))return out;Item item=Item.REGISTRY.getObject(id);int damage=source.getInteger("Damage");String canonical=RENAMES.get(name);
  if("minecraft:fish".equals(name)&&damage>=0&&damage<4)canonical="minecraft:"+new String[]{"cod","salmon","tropical_fish","pufferfish"}[damage];
  if("minecraft:cooked_fish".equals(name)&&damage>=0&&damage<2)canonical="minecraft:"+new String[]{"cooked_cod","cooked_salmon"}[damage];
  if("minecraft:golden_apple".equals(name)&&damage==1)canonical="minecraft:enchanted_golden_apple";
  if(item instanceof ItemBlock&&!name.equals("minecraft:skull")&&!name.equals("minecraft:banner")&&!name.equals("minecraft:bed"))try{Block block=((ItemBlock)item).getBlock();NBTTagCompound state=Snbt.compound(LegacyStates.encode(block.getStateFromMeta(item.getMetadata(damage))));if(!state.hasKey("EpochLegacyVersion"))canonical=state.getString("Name");}catch(Exception ignored){}
  if(canonical!=null){out.setString("id",canonical);out.removeTag("Damage");}
  else if(item.isDamageable()){out.removeTag("Damage");{NBTTagCompound tag=out.getCompoundTag("tag");tag.setInteger("Damage",damage);out.setTag("tag",tag);}}
  else if(damage==0)out.removeTag("Damage");
  if(out.hasKey("tag",10))out.setTag("tag",encodePayload(out.getCompoundTag("tag")));
  return out;
 }
 private static synchronized void initialize(){if(initialized)return;for(Item item:Item.REGISTRY){ResourceLocation id=item.getRegistryName();if(id==null)continue;int limit=item.getHasSubtypes()?16:1;for(int damage=0;damage<limit;damage++){NBTTagCompound local=new NBTTagCompound();local.setString("id",id.toString());local.setByte("Count",(byte)1);local.setShort("Damage",(short)damage);NBTTagCompound canonical=encode(local);if(canonical.hasKey("Damage"))continue;String name=canonical.getString("id");if(!REVERSE.containsKey(name))REVERSE.put(name,local);}}initialized=true;}
 static NBTTagList decodeList(NBTTagList source){NBTTagList out=new NBTTagList();for(int n=0;n<source.tagCount();n++)out.appendTag(decode(source.getCompoundTagAt(n)));return out;}
 static NBTTagList decodePlayerInventory(NBTTagList source){
  Set<Integer> occupied=new HashSet<Integer>();
  for(int n=0;n<source.tagCount();n++){
   if(!(source.get(n) instanceof NBTTagCompound))throw new IllegalArgumentException("Player inventory entries must be compounds");
   NBTTagCompound item=source.getCompoundTagAt(n);
   if(!item.hasKey("Slot",1))throw new IllegalArgumentException("Player inventory Slot must be a byte");
   int slot=item.getByte("Slot")&255;
   if(!(slot<36||(slot>=100&&slot<=103)||slot==150))throw new IllegalArgumentException("1.12.2 cannot represent player inventory slot "+slot);
   if(!occupied.add(slot))throw new IllegalArgumentException("Duplicate player inventory slot "+slot);
  }
  return decodeList(source);
 }
 static NBTTagCompound decode(NBTTagCompound source){initialize();String name=source.getString("id");NBTTagCompound prototype=REVERSE.get(name);if(prototype==null)throw new IllegalArgumentException("1.12.2 cannot represent inventory item "+name+"; store it outside the shared region first");NBTTagCompound local=source.copy();local.setString("id",prototype.getString("id"));local.setShort("Damage",prototype.getShort("Damage"));Item item=Item.REGISTRY.getObject(new ResourceLocation(local.getString("id")));
  if(local.hasKey("tag",10)){NBTTagCompound tag=decodePayload(local.getCompoundTag("tag"));if(item.isDamageable()&&tag.hasKey("Damage",99)){int damage=tag.getInteger("Damage");if(damage<0||damage>32767)throw new IllegalArgumentException("Unrepresentable tool damage");local.setShort("Damage",(short)damage);tag.removeTag("Damage");}if(tag.getKeySet().isEmpty())local.removeTag("tag");else local.setTag("tag",tag);}
  ItemStack stack=new ItemStack(local);if(stack.isEmpty())throw new IllegalArgumentException("Inventory item cannot be decoded: "+name);NBTTagCompound actual=encode(stack.writeToNBT(new NBTTagCompound()));if(source.hasKey("Slot"))actual.setTag("Slot",source.getTag("Slot").copy());if(!Handoff.preserves(source,actual))throw new IllegalArgumentException("1.12.2 would lose inventory NBT for "+name+"; switching cancelled before world changes");return local;
 }
 static NBTTagCompound encodePayload(NBTTagCompound source){NBTTagCompound out=source.copy();for(String key:source.getKeySet()){NBTBase tag=source.getTag(key);if("Items".equals(key)&&tag instanceof NBTTagList)out.setTag(key,encodeList((NBTTagList)tag));else if(tag instanceof NBTTagCompound)out.setTag(key,encodePayload((NBTTagCompound)tag));}return out;}
 static NBTTagCompound decodePayload(NBTTagCompound source){NBTTagCompound out=source.copy();for(String key:source.getKeySet()){NBTBase tag=source.getTag(key);if("Items".equals(key)&&tag instanceof NBTTagList)out.setTag(key,decodeList((NBTTagList)tag));else if(tag instanceof NBTTagCompound)out.setTag(key,decodePayload((NBTTagCompound)tag));}return out;}
}

