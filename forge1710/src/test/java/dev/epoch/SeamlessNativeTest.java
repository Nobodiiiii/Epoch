package dev.epoch;
import dev.epoch.bridge.*;
public final class SeamlessNativeTest {
 static void require(boolean value,String label){if(!value)throw new AssertionError(label);}
 public static void main(String[] args)throws Exception {
 cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),java.util.Collections.emptyList()); net.minecraft.init.Bootstrap.func_151354_b();
 require(LegacyStates.decode("{Name:\"minecraft:coarse_dirt\"}")!=null,"Coarse dirt unavailable");
 require(LegacyStates.decode("{Name:\"minecraft:oak_leaves\",Properties:{persistent:\"false\",distance:\"2\",waterlogged:\"false\"}}")!=null,"Dry leaves rejected");
 require(LegacyStates.decode("{Name:\"minecraft:oak_leaves\",Properties:{persistent:\"false\",distance:\"2\",waterlogged:\"true\"}}") == null,"Water discarded");
 require(LegacyStates.decode("{Name:\"minecraft:oak_stairs\",Properties:{facing:\"north\",half:\"bottom\",shape:\"inner_left\",waterlogged:\"false\"}}")!=null,"Stair corner rejected");
 require(LegacyStates.decode("{Name:\"minecraft:nether_portal\",Properties:{axis:\"x\"}}")!=null,"Portal alias");
 require(LegacyStates.decode("{Name:\"minecraft:cauldron\"}")!=null,"Empty cauldron");
 require(LegacyStates.decode("{Name:\"minecraft:water_cauldron\",Properties:{level:\"3\"}}")!=null,"Water cauldron");
 String[] names={"minecraft:potted_blue_orchid","minecraft:creeper_head"};
 String[] source={"{Name:\"minecraft:potted_blue_orchid\"}","{Name:\"minecraft:creeper_head\",Properties:{rotation:\"7\"}}"};
 for(int i=0;i<source.length;i++) {
  BlockPayloads.Pair pair=BlockPayloads.convert(source[i],null,"1.7.10");LegacyStates.State state=LegacyStates.decode(pair.state);require(state!=null,"Projected native state");
  net.minecraft.tileentity.TileEntity tile=state.block.createTileEntity(null,state.meta);require(tile!=null,"Projected tile factory");LegacyNbt.Tag projected=LegacyNbt.parse(pair.payload);Handoff.convertTile(projected,false);net.minecraft.nbt.NBTTagCompound tag=LegacyNbt.nativeTag(projected);
  tag.setInteger("x",0);tag.setInteger("y",64);tag.setInteger("z",0);tile.readFromNBT(tag);net.minecraft.nbt.NBTTagCompound back=new net.minecraft.nbt.NBTTagCompound();tile.writeToNBT(back);LegacyNbt.Tag canonical=LegacyNbt.from(back);Handoff.convertTile(canonical,true);String saved=LegacyNbt.encode(canonical);
  BlockPayloads.Pair restored=BlockPayloads.convert(pair.state,saved,"1.20.1");require(SnbtFields.parse(restored.state).get("Name").equals(names[i]),"Native NBT roundtrip "+names[i]);
 }
 System.out.println("Seamless native 1.7.10 checks passed");
 }
}
