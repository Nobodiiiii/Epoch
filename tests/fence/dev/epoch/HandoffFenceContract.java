package dev.epoch;

import dev.epoch.bridge.Snapshot;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Shared scenarios, compiled against each adapter's native queue, inventory and section fixture. */
final class HandoffFenceContract {
    interface Native {
        void enqueue(Runnable task);
        void pump(BooleanSupplier done);
        void reset();
        void editInventory();
        void placeStone();
        Snapshot capture() throws Exception;
        void verifyImported(Snapshot snapshot) throws Exception;
        HandoffFence.Marker wire(long id);
    }
    interface Action { void run() throws Exception; }
    static int checks;
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static void rejects(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Export proceeded without a valid input acknowledgement");
    }
    static void network(Runnable task) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> { try { task.run(); } catch (Throwable error) { failure.set(error); } }, "Fence test Netty");
        thread.start();
        try { thread.join(5000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        if (thread.isAlive() || failure.get() != null) throw new AssertionError("Network fixture failed", failure.get());
    }
    static void run(String version, Native nativeState) throws Exception {
        Object connection = new Object();
        for (int cycle = 0; cycle < 2; cycle++) {
            nativeState.reset();
            network(() -> {
                // Tasks represent decoded native input waiting while the server is busy/frozen.
                for (int i=0;i<1500;i++) nativeState.enqueue(() -> {});
                nativeState.enqueue(nativeState::editInventory);
                nativeState.enqueue(nativeState::placeStone);
            });
            Snapshot before = nativeState.capture();
            check(!before.inventory.contains("minecraft:stone") && before.palette.get(0).contains("air"),
                    "old ordering did not expose stale inventory and terrain");
            HandoffFence.drain(connection, id -> network(() ->
                    HandoffFence.receive(nativeState.wire(id), connection, nativeState::enqueue)),
                    () -> true, nativeState::pump, 2000);
            Snapshot after = nativeState.capture();
            check(after.inventory.contains("minecraft:stone") && after.palette.get(0).contains("stone"),
                    "input marker overtook native inventory/placement tasks");
            Path path = Files.createTempFile("epoch-fence-" + version + "-", ".json.gz");
            try {
                after.write(path); Snapshot saved = Snapshot.read(path);
                nativeState.verifyImported(saved); checks++;
            } finally { Files.deleteIfExists(path); }
        }
        long[] stale = {0};
        rejects(() -> HandoffFence.drain(connection,id -> stale[0]=id,() -> true,nativeState::pump,15));
        rejects(() -> HandoffFence.drain(connection,id -> network(() ->
                HandoffFence.receive(nativeState.wire(stale[0]),connection,nativeState::enqueue)),() -> true,nativeState::pump,15));
        rejects(() -> HandoffFence.drain(connection,id -> network(() ->
                HandoffFence.receive(nativeState.wire(id),new Object(),nativeState::enqueue)),() -> true,nativeState::pump,15));
        rejects(() -> HandoffFence.drain(connection,id -> {},() -> false,nativeState::pump,1000));
        Thread.currentThread().interrupt();
        try { rejects(() -> HandoffFence.drain(connection,id -> {},() -> true,nativeState::pump,1000)); }
        finally { Thread.interrupted(); }
        HandoffFence.drain(connection,id -> network(() ->
                HandoffFence.receive(nativeState.wire(id),connection,nativeState::enqueue)),() -> true,nativeState::pump,2000);
        checks++;
        System.out.println("PASS: " + version + " native input fence " + checks
                + " checks; 2 queued inventory/stone snapshot roundtrips; stale/wrong connection/timeout/disconnect/interruption/retry");
    }
}
