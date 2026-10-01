package dev.epoch.bridge;

import java.util.HashSet;
import java.util.Set;

/** Validates serialized player slots before Minecraft can ignore or overwrite an entry. */
public final class InventorySlots {
    private final boolean supportsOffhand;
    private final Set<Integer> occupied = new HashSet<Integer>();

    public InventorySlots(boolean supportsOffhand) {
        this.supportsOffhand = supportsOffhand;
    }

    public int claim(byte encodedSlot) {
        int slot = encodedSlot & 255;
        if (!(slot < 36 || slot >= 100 && slot <= 103 || supportsOffhand && slot == 150))
            throw new IllegalArgumentException("Unsupported player inventory slot " + slot);
        if (!occupied.add(slot))
            throw new IllegalArgumentException("Duplicate player inventory slot " + slot);
        return slot;
    }
}
