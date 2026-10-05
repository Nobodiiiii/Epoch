package dev.epoch;

import net.minecraft.nbt.*;
import net.minecraft.item.ItemStack;

/** Lossless transport for carried items that this registry cannot represent. */
final class ItemTransfer {
  static final String ID = "epoch:unavailable_item";
  static final String PAYLOAD = "EpochUnavailableItem";

  static CompoundNBT original(CompoundNBT source) throws Exception {
    validate(source);
    if (!ID.equals(source.getString("id"))) return source.copy();
    CompoundNBT tag = source.getCompound("tag");
    if (!tag.contains(PAYLOAD, 8) || tag.getInt("EpochItemFormat") != 1)
      throw new IllegalArgumentException("Invalid Epoch item envelope");
    CompoundNBT result = JsonToNBT.parseTag(tag.getString(PAYLOAD));
    if (ID.equals(result.getString("id")) || result.contains("Count") || result.contains("Slot"))
      throw new IllegalArgumentException("Invalid nested Epoch item envelope");
    result.putByte("Count", source.getByte("Count"));
    if (source.contains("Slot")) result.putByte("Slot", source.getByte("Slot"));
    validate(result);
    return result;
  }

  private static void validate(CompoundNBT item) {
    if (!item.contains("id", 8) || !item.getString("id").matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
        || "minecraft:air".equals(item.getString("id"))
        || !item.contains("Count", 1) || item.getByte("Count") <= 0
        || (item.contains("Slot") && !item.contains("Slot", 1)))
      throw new IllegalArgumentException("Invalid carried item ID, count or slot");
  }

  static CompoundNBT decode(CompoundNBT source) throws Exception {
    CompoundNBT canonical = original(source);
    try {
      return nativeItem(canonical);
    } catch (IllegalArgumentException unavailable) {
      // Count and Slot belong to the live stack, never to its immutable payload.
      // Splitting, moving or discarding the envelope must not duplicate the original.
      CompoundNBT payload = canonical.copy();
      payload.remove("Count");
      payload.remove("Slot");
      CompoundNBT tag = new CompoundNBT();
      tag.putInt("EpochItemFormat", 1);
      String encoded = payload.toString();
      // NBT strings use modified UTF-8 with a 65535-byte limit. Check before import,
      // so an oversized envelope cannot make a later player save fail.
      new java.io.DataOutputStream(new java.io.ByteArrayOutputStream()).writeUTF(encoded);
      tag.putString(PAYLOAD, encoded);
      tag.putString("EpochOriginalId", canonical.getString("id"));
      CompoundNBT envelope = new CompoundNBT();
      envelope.putString("id", ID);
      envelope.putByte("Count", canonical.getByte("Count"));
      if (canonical.contains("Slot")) envelope.putByte("Slot", canonical.getByte("Slot"));
      envelope.put("tag", tag);
      return nativeItem(envelope);
    }
  }

  private static CompoundNBT nativeItem(CompoundNBT source) throws Exception {
    CompoundNBT expected = source.copy();
    expected.remove("Slot");
    ItemStack stack = ItemStack.of(expected.copy());
    if (stack.isEmpty() || !Handoff.preservesNbt(expected, stack.save(new CompoundNBT())))
      throw new IllegalArgumentException("Item cannot roundtrip: " + source.getString("id"));
    return source.copy();
  }

  static ListNBT decodeList(ListNBT source) throws Exception {
    ListNBT result = new ListNBT();
    for (int n = 0; n < source.size(); n++) {
      if (!(source.get(n) instanceof CompoundNBT))
        throw new IllegalArgumentException("Inventory entries must be compounds");
      result.add(decode(source.getCompound(n)));
    }
    return result;
  }
}
