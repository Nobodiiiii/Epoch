package dev.epoch;

import net.minecraft.nbt.*;
import net.minecraft.item.ItemStack;

/** Lossless transport for carried items that this registry cannot represent. */
final class ItemTransfer {
  static final String ID = "epoch:unavailable_item";
  static final String PAYLOAD = "EpochUnavailableItem";

  static NBTTagCompound original(NBTTagCompound source) throws Exception {
    validate(source);
    if (!ID.equals(source.getString("id"))) return (NBTTagCompound)source.copy();
    NBTTagCompound tag = source.getCompoundTag("tag");
    if (!tag.hasKey(PAYLOAD, 8) || tag.getInteger("EpochItemFormat") != 1)
      throw new IllegalArgumentException("Invalid Epoch item envelope");
    NBTTagCompound result = EnvironmentTransfer.parse(tag.getString(PAYLOAD));
    if (ID.equals(result.getString("id")) || result.hasKey("Count") || result.hasKey("Slot"))
      throw new IllegalArgumentException("Invalid nested Epoch item envelope");
    result.setByte("Count", source.getByte("Count"));
    if (source.hasKey("Slot")) result.setByte("Slot", source.getByte("Slot"));
    validate(result);
    return result;
  }

  private static void validate(NBTTagCompound item) {
    if (!item.hasKey("id", 8) || !item.getString("id").matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
        || "minecraft:air".equals(item.getString("id"))
        || !item.hasKey("Count", 1) || item.getByte("Count") <= 0
        || (item.hasKey("Slot") && !item.hasKey("Slot", 1)))
      throw new IllegalArgumentException("Invalid carried item ID, count or slot");
  }

  static NBTTagCompound decode(NBTTagCompound source) throws Exception {
    NBTTagCompound canonical = original(source);
    try {
      return nativeItem(canonical);
    } catch (IllegalArgumentException unavailable) {
      // Count and Slot belong to the live stack, never to its immutable payload.
      // Splitting, moving or discarding the envelope must not duplicate the original.
      NBTTagCompound payload = (NBTTagCompound)canonical.copy();
      payload.removeTag("Count");
      payload.removeTag("Slot");
      NBTTagCompound tag = new NBTTagCompound();
      tag.setInteger("EpochItemFormat", 1);
      String encoded = LegacyNbt.encode(payload);
      // NBT strings use modified UTF-8 with a 65535-byte limit. Check before import,
      // so an oversized envelope cannot make a later player save fail.
      new java.io.DataOutputStream(new java.io.ByteArrayOutputStream()).writeUTF(encoded);
      tag.setString(PAYLOAD, encoded);
      tag.setString("EpochOriginalId", canonical.getString("id"));
      NBTTagCompound envelope = new NBTTagCompound();
      envelope.setString("id", ID);
      envelope.setByte("Count", canonical.getByte("Count"));
      if (canonical.hasKey("Slot")) envelope.setByte("Slot", canonical.getByte("Slot"));
      envelope.setTag("tag", tag);
      return nativeItem(envelope);
    }
  }

  private static NBTTagCompound nativeItem(NBTTagCompound source) throws Exception {
    LegacyNbt.Tag local = LegacyNbt.from(source);
    Handoff.convertItems(local, false);
    NBTTagCompound nativeTag = LegacyNbt.nativeTag(local);
    ItemStack stack = ItemStack.loadItemStackFromNBT(nativeTag);
    if (stack == null || stack.stackSize <= 0)
      throw new IllegalArgumentException("Item cannot be decoded");
    NBTTagCompound actual = new NBTTagCompound();
    stack.writeToNBT(actual);
    if (source.hasKey("Slot")) actual.setByte("Slot", source.getByte("Slot"));
    LegacyNbt.Tag restored = LegacyNbt.from(actual);
    Handoff.convertItems(restored, true);
    if (!LegacyNbt.preserves(LegacyNbt.from(source), restored))
      throw new IllegalArgumentException("Item cannot roundtrip: " + source.getString("id"));
    return nativeTag;
  }

  static NBTTagList decodeList(NBTTagList source) throws Exception {
    NBTTagList result = new NBTTagList();
    for (int n = 0; n < source.tagCount(); n++) {
      if (source.func_150303_d() != 10)
        throw new IllegalArgumentException("Inventory entries must be compounds");
      result.appendTag(decode(source.getCompoundTagAt(n)));
    }
    return result;
  }
}
