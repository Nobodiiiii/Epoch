package dev.epoch;

import net.minecraft.nbt.*;
import net.minecraft.item.*;

/** Exercises real ItemStack save/load and the carried-item paths without a world. */
final class ItemTransferTest {
  static int checks;
  static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
  interface Action { void run() throws Exception; }
  static void rejects(Action action) throws Exception {
    checks++;
    try { action.run(); } catch (IllegalArgumentException | java.io.IOException expected) { return; }
    throw new AssertionError("Malformed item accepted");
  }
  static NBTTagCompound parse(String text) throws Exception { return EnvironmentTransfer.parse(text); }
  static NBTTagCompound canonical(NBTTagCompound local) throws Exception { LegacyNbt.Tag result=LegacyNbt.from(local);Handoff.convertItems(result,true);return LegacyNbt.nativeTag(result); }
  static NBTTagCompound saveLoad(NBTTagCompound local) throws Exception {
    ItemStack stack=ItemStack.loadItemStackFromNBT((NBTTagCompound)local.copy());
    NBTTagCompound saved=stack.writeToNBT(new NBTTagCompound());
    if(local.hasKey("Slot"))saved.setByte("Slot",local.getByte("Slot"));
    return canonical(saved);
  }
  static void run() throws Exception {
    // Headless bootstrap has no active mod container to provide the epoch namespace.
    java.lang.reflect.Method main=cpw.mods.fml.common.registry.GameData.class.getDeclaredMethod("getMain");main.setAccessible(true);
    java.lang.reflect.Method register=cpw.mods.fml.common.registry.GameData.class.getDeclaredMethod("registerItem",Item.class,String.class,int.class);register.setAccessible(true);
    register.invoke(main.invoke(null),Epoch.UNAVAILABLE_ITEM,ItemTransfer.ID,4090);
    String template="{id:'minecraft:shaper_armor_trim_smithing_template',Count:7b,Slot:2b,tag:{EpochTest:'中文',Custom:[1,2,3]}}";
    NBTTagCompound source=parse(template), untouched=(NBTTagCompound)source.copy();
    NBTTagCompound transported=saveLoad(ItemTransfer.decode(source));
    check(source.equals(untouched), "source mutated");
    check(ItemTransfer.original(transported).equals(source), "smithing template lost NBT or count");
    check(Epoch.VERSION.equals("1.20.1") != ItemTransfer.ID.equals(transported.getString("id")), "wrong template availability");
    NBTTagCompound missing=parse(template.replace("minecraft:shaper_armor_trim_smithing_template","missing:future_item"));
    NBTTagCompound envelope=saveLoad(ItemTransfer.decode(missing));
    check(envelope.getString("id").equals(ItemTransfer.ID), "missing item not carried");
    for(int cycle=0;cycle<3;cycle++)envelope=saveLoad(ItemTransfer.decode(envelope));
    check(ItemTransfer.original(envelope).equals(missing), "repeated handoff nested or lost payload");
    envelope.setByte("Count",(byte)3);envelope.setByte("Slot",(byte)5);
    NBTTagCompound split=ItemTransfer.original(saveLoad(ItemTransfer.decode(envelope)));
    check(split.getByte("Count")==3 && split.getByte("Slot")==5, "split/move restored stale count or slot");
    check(split.getCompoundTag("tag").equals(missing.getCompoundTag("tag")), "split changed item NBT");
    // A sealed diamond models returning to a registry that supports the original item.
    envelope.getCompoundTag("tag").setString(ItemTransfer.PAYLOAD,
        envelope.getCompoundTag("tag").getString(ItemTransfer.PAYLOAD).replace("missing:future_item","minecraft:diamond"));
    NBTTagCompound restored=saveLoad(ItemTransfer.decode(envelope));
    check(restored.equals(ItemTransfer.original(envelope)), "compatible return did not restore the item");
    NBTTagCompound diamond=parse("{id:'minecraft:diamond',Count:7b,Slot:1b}");
    check(ItemTransfer.original(saveLoad(ItemTransfer.decode(diamond))).equals(diamond), "normal item changed");
    NBTTagCompound opaque=parse("{id:'minecraft:diamond',Count:1b,Slot:0b,FutureRoot:'preserve me'}");
    check(ItemTransfer.original(saveLoad(ItemTransfer.decode(opaque))).equals(opaque), "unsupported root NBT lost");
    NBTTagCompound ender=EnvironmentTransfer.preparePlayer(null,EnvironmentTransferTest.BASE.replace("EnderItems:[]","EnderItems:["+template+"]"));
    check(ItemTransfer.original(saveLoad(ender.getTagList("EnderItems",10).getCompoundTagAt(0))).equals(source), "ender path lost template");
    Handoff.prepareInventory("["+template+"]"); checks++;
    check(ItemTransfer.decodeList(new NBTTagList()).tagCount()==0, "empty inventory resurrected a discarded item");
    rejects(()->ItemTransfer.decode(parse("{id:'epoch:unavailable_item',Count:1b}")));
    rejects(()->ItemTransfer.decode(parse("{id:'minecraft:stone',Count:0b}")));
    rejects(()->ItemTransfer.decode(parse("{id:'minecraft:stone',Count:1}")));
    rejects(()->ItemTransfer.decode(parse("{id:'minecraft:air',Count:1b}")));
    rejects(()->ItemTransfer.decode(parse("{id:'missing:large_item',Count:1b,tag:{Large:'"+new String(new char[70000]).replace('\0','x')+"'}}")));
    System.out.println("PASS: "+Epoch.VERSION+" carried item "+checks+" checks");
  }
}
