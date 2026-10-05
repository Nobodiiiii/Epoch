package dev.epoch;

import net.minecraft.init.*;
import net.minecraft.nbt.NBTTagCompound;

/** No client/world is launched. Exercises real 1.7 NBT and registered vanilla blocks. */
public final class LegacySelfTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),java.util.Collections.emptyList());
  Bootstrap.func_151354_b();
  UnknownTileLifecycleTest.run();
  ClientTerrainTest.run();
  check(LegacyStates.encode(Blocks.skull,1).contains("EpochLegacyVersion"),"Skull sentinel must retain raw legacy state");
  String source="{text:\"a \\\"quote\\\" and \\\\ slash\",small:7b,short:32s,big:1234567890123L,float:1.5f,double:2.5d,items:[{id:\"minecraft:diamond\",Count:7b}],bytes:[B;1B,-2B],ints:[I;4,-9]}";
  LegacyNbt.Tag parsed=LegacyNbt.parse(source);String canonical=LegacyNbt.encode(parsed);NBTTagCompound nativeTag=LegacyNbt.nativeTag(parsed);check(LegacyNbt.preserves(parsed,LegacyNbt.from(nativeTag))&&LegacyNbt.preserves(LegacyNbt.from(nativeTag),parsed),"Canonical SNBT/binary roundtrip changed types or payload");
  boolean rejected=false;try{LegacyNbt.parse("{x:[L;1L]}");}catch(java.io.IOException expected){rejected=true;}check(rejected,"Long array silently narrowed");
  LegacyStates.State grass=LegacyStates.decode("{Name:\"minecraft:grass_block\",Properties:{snowy:\"false\"}}");check(grass!=null&&grass.block==Blocks.grass,"Grass compatibility");
  check(LegacyStates.decode("{Name:\"minecraft:granite\"}")==null,"Granite incorrectly mapped to old stone metadata");
  check(LegacyStates.decode("{Name:\"minecraft:wet_sponge\"}")==null,"Wet sponge incorrectly accepted");
  check(LegacyStates.decode("{Name:\"minecraft:cherry_planks\"}")==null,"Cherry incorrectly accepted");
  LegacyStates.State chest=LegacyStates.decode("{Name:\"minecraft:chest\",Properties:{facing:\"north\",type:\"single\",waterlogged:\"false\"}}");check(chest!=null&&chest.block==Blocks.chest&&chest.meta==2,"Chest orientation/default waterlogged mapping");
  check(LegacyStates.decode("{Name:\"minecraft:chest\",Properties:{facing:\"north\",type:\"single\",waterlogged:\"true\"}}")==null,"Waterlogged chest lost water");
  LegacyStates.State planks=LegacyStates.decodeItem("minecraft:birch_planks");check(planks!=null&&planks.block==Blocks.planks&&planks.meta==2,"Flattened item metadata");
  String raw=LegacyStates.encode(Blocks.stone,6);check(raw.contains("EpochLegacyVersion"),"Unrepresentable legacy metadata missing origin marker");check(LegacyStates.decode(raw).meta==6,"Raw metadata roundtrip");
  check(LegacyStates.decode(raw.replace("1.7.10","1.12.2"))==null,"Foreign raw metadata accepted");
  LegacyNbt.Tag items=LegacyNbt.parse("{Items:[{id:\"minecraft:diamond\",Count:7b,Slot:0b,tag:{display:{Name:\"Epoch sentinel\"}}}]}" );
  Handoff.convertItems(items,false);check(LegacyNbt.map(LegacyNbt.list(LegacyNbt.map(items).get("Items")).get(0)).get("id").type==2,"Inventory id not converted to legacy short");Handoff.convertItems(items,true);check(LegacyNbt.encode(items).contains("minecraft:diamond"),"Inventory registry roundtrip");
  LegacyNbt.Tag modernChest=LegacyNbt.parse("{id:\"minecraft:chest\",x:0,y:0,z:0,Items:[{id:\"minecraft:diamond\",Count:7b,Slot:0b}]}" );
  LegacyNbt.Tag legacyChest=LegacyNbt.parse(LegacyNbt.encode(modernChest));Handoff.convertTile(legacyChest,false);net.minecraft.tileentity.TileEntityChest chestTile=new net.minecraft.tileentity.TileEntityChest();chestTile.readFromNBT(LegacyNbt.nativeTag(legacyChest));NBTTagCompound output=new NBTTagCompound();chestTile.writeToNBT(output);LegacyNbt.Tag restored=LegacyNbt.from(output);Handoff.convertTile(restored,true);check(LegacyNbt.preserves(modernChest,restored),"Compatible chest cannot preserve payload");
  LegacyNbt.map(modernChest).put("LootTable",new LegacyNbt.Tag(8,"minecraft:test"));check(!LegacyNbt.preserves(modernChest,restored),"Unknown tile field silently discarded");
  Epoch.UnknownTile unknown=new Epoch.UnknownTile();unknown.originalState="{Name:\"minecraft:cherry_planks\"}";unknown.originalNbt="{id:\"minecraft:chest\",x:3,y:64,z:4,Items:[]}";unknown.sourceVersion="1.20.1";net.minecraft.item.ItemStack copied=new net.minecraft.item.ItemStack(Items.stick);unknown.copyToItem(copied);Epoch.UnknownTile moved=new Epoch.UnknownTile();moved.xCoord=9;moved.yCoord=70;moved.zCoord=-8;moved.readFromItem(copied);check(moved.originalState.equals(unknown.originalState)&&moved.originalNbt.equals(unknown.originalNbt)&&moved.sourceVersion.equals(unknown.sourceVersion),"Unknown item lost original payload");check(moved.xCoord==9&&moved.yCoord==70&&moved.zCoord==-8,"Placed unknown inherited old coordinates");
  check(Handoff.prepareInventory("[{id:\"minecraft:diamond\",Count:1b,Slot:35b},{id:\"minecraft:diamond_helmet\",Count:1b,Slot:103b}]").tagCount()==2,"Main/armor inventory slots rejected");
  for(String badInventory:new String[]{"[{id:\"minecraft:diamond\",Count:1b,Slot:-106b}]","[{id:\"minecraft:diamond\",Count:1b,Slot:36b}]","[{id:\"minecraft:diamond\",Count:1b,Slot:104b}]","[{id:\"minecraft:diamond\",Count:1b,Slot:0b},{id:\"minecraft:diamond\",Count:2b,Slot:0b}]","[{id:\"minecraft:diamond\",Count:1b,Slot:0b,LostField:\"must preserve\"}]"}){boolean failed=false;try{Handoff.prepareInventory(badInventory);}catch(IllegalArgumentException expectedFailure){failed=true;}check(failed,"Unsupported inventory entry was silently accepted: "+badInventory);}
  System.out.println("PASS: canonical SNBT/binary NBT, metadata, unsupported states, directional/default properties, raw state origin, inventory IDs");
 }
}

