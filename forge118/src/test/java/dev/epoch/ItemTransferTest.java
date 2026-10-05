package dev.epoch;

import net.minecraft.nbt.*;
import net.minecraft.world.item.*;

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
  static CompoundTag parse(String text) throws Exception { return TagParser.parseTag(text); }
  static CompoundTag canonical(CompoundTag local) throws Exception { return local.copy(); }
  static CompoundTag saveLoad(CompoundTag local) throws Exception {
    ItemStack stack=ItemStack.of(local.copy());
    CompoundTag saved=stack.save(new CompoundTag());
    if(local.contains("Slot"))saved.putByte("Slot",local.getByte("Slot"));
    return canonical(saved);
  }
  static void run() throws Exception {
    ((net.minecraftforge.registries.ForgeRegistry<Item>)net.minecraftforge.registries.ForgeRegistries.ITEMS).unfreeze();
    java.lang.reflect.Method unfreeze=net.minecraft.core.Registry.ITEM.getClass().getMethod("unfreeze");unfreeze.setAccessible(true);unfreeze.invoke(net.minecraft.core.Registry.ITEM);
    net.minecraftforge.registries.ForgeRegistries.ITEMS.register(new Item(new Item.Properties()).setRegistryName(ItemTransfer.ID));
    String template="{id:'minecraft:shaper_armor_trim_smithing_template',Count:7b,Slot:2b,tag:{EpochTest:'中文',Custom:[1,2,3]}}";
    CompoundTag source=parse(template), untouched=source.copy();
    CompoundTag transported=saveLoad(ItemTransfer.decode(source));
    check(source.equals(untouched), "source mutated");
    check(ItemTransfer.original(transported).equals(source), "smithing template lost NBT or count");
    check(Epoch.VERSION.equals("1.20.1") != ItemTransfer.ID.equals(transported.getString("id")), "wrong template availability");
    CompoundTag missing=parse(template.replace("minecraft:shaper_armor_trim_smithing_template","missing:future_item"));
    CompoundTag envelope=saveLoad(ItemTransfer.decode(missing));
    check(envelope.getString("id").equals(ItemTransfer.ID), "missing item not carried");
    for(int cycle=0;cycle<3;cycle++)envelope=saveLoad(ItemTransfer.decode(envelope));
    check(ItemTransfer.original(envelope).equals(missing), "repeated handoff nested or lost payload");
    envelope.putByte("Count",(byte)3);envelope.putByte("Slot",(byte)5);
    CompoundTag split=ItemTransfer.original(saveLoad(ItemTransfer.decode(envelope)));
    check(split.getByte("Count")==3 && split.getByte("Slot")==5, "split/move restored stale count or slot");
    check(split.getCompound("tag").equals(missing.getCompound("tag")), "split changed item NBT");
    // A sealed diamond models returning to a registry that supports the original item.
    envelope.getCompound("tag").putString(ItemTransfer.PAYLOAD,
        envelope.getCompound("tag").getString(ItemTransfer.PAYLOAD).replace("missing:future_item","minecraft:diamond"));
    CompoundTag restored=saveLoad(ItemTransfer.decode(envelope));
    check(restored.equals(ItemTransfer.original(envelope)), "compatible return did not restore the item");
    CompoundTag diamond=parse("{id:'minecraft:diamond',Count:7b,Slot:1b}");
    check(ItemTransfer.original(saveLoad(ItemTransfer.decode(diamond))).equals(diamond), "normal item changed");
    CompoundTag opaque=parse("{id:'minecraft:diamond',Count:1b,Slot:0b,FutureRoot:'preserve me'}");
    check(ItemTransfer.original(saveLoad(ItemTransfer.decode(opaque))).equals(opaque), "unsupported root NBT lost");
    CompoundTag ender=EnvironmentTransfer.preparePlayer(null,EnvironmentTransferTest.BASE.replace("EnderItems:[]","EnderItems:["+template+"]"));
    check(ItemTransfer.original(saveLoad(ender.getList("EnderItems",10).getCompound(0))).equals(source), "ender path lost template");
    Epoch.validateInventory(ItemTransfer.decodeList(parse("{Items:["+template+"]}").getList("Items",10))); checks++;
    check(ItemTransfer.decodeList(new ListTag()).isEmpty(), "empty inventory resurrected a discarded item");
    rejects(()->ItemTransfer.decode(parse("{id:'epoch:unavailable_item',Count:1b}")));
    rejects(()->ItemTransfer.decode(parse("{id:'minecraft:stone',Count:0b}")));
    rejects(()->ItemTransfer.decode(parse("{id:'minecraft:stone',Count:1}")));
    rejects(()->ItemTransfer.decode(parse("{id:'minecraft:air',Count:1b}")));
    rejects(()->ItemTransfer.decode(parse("{id:'missing:large_item',Count:1b,tag:{Large:'"+new String(new char[70000]).replace('\0','x')+"'}}")));
    System.out.println("PASS: "+Epoch.VERSION+" carried item "+checks+" checks");
  }
}
