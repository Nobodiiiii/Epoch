import dev.epoch.bridge.InventorySlots;

/** Protect against Inventory.load silently skipping or overwriting serialized player entries. */
public final class InventorySlotsTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) {
        InventorySlots modern = new InventorySlots(true);
        for (int slot = 0; slot < 36; slot++)
            check(modern.claim((byte) slot) == slot, "Main inventory slot rejected: " + slot);
        for (int slot = 100; slot < 104; slot++)
            check(modern.claim((byte) slot) == slot, "Armor slot rejected: " + slot);
        check(modern.claim((byte) -106) == 150, "Signed offhand byte did not normalize to slot 150");
        rejects(() -> modern.claim((byte) 0), "Duplicate hotbar slot would overwrite an item");
        rejects(() -> modern.claim((byte) 100), "Duplicate armor slot would overwrite an item");
        rejects(() -> modern.claim((byte) 150), "Offhand alias bypassed duplicate detection");
        for (int slot : new int[] {36, 39, 40, 99, 104, 149, 151, 255}) {
            final byte encoded = (byte) slot;
            rejects(() -> new InventorySlots(true).claim(encoded), "Ignored player slot accepted: " + slot);
        }
        InventorySlots legacy = new InventorySlots(false);
        check(legacy.claim((byte) 35) == 35 && legacy.claim((byte) 103) == 103,
              "Legacy inventory/armor compatibility changed");
        rejects(() -> legacy.claim((byte) -106), "Legacy client accepted unavailable offhand");
        check(new InventorySlots(true).claim((byte) 0) == 0, "Slot ownership leaked between snapshots");
        System.out.println("PASS: " + checks + " player inventory slot boundary/duplicate assertions");
    }
}
