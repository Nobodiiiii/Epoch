package dev.epoch;

import dev.epoch.bridge.InputFence;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

/** An ordered marker after frozen client input, acknowledged only on the native server task queue. */
public final class HandoffFence {
    private static final InputFence FENCE = new InputFence();
    private static SimpleChannel network;

    static void register() {
        network = NetworkRegistry.newSimpleChannel(new ResourceLocation("epoch", "handoff"),
                () -> "1", "1"::equals, "1"::equals);
        network.registerMessage(0, Marker.class, Marker::encode, Marker::decode, HandoffFence::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    static long drain(MinecraftServer server, Connection connection) throws Exception {
        return drain(connection,
                id -> Minecraft.getInstance().execute(() -> network.sendToServer(new Marker(id))),
                connection::isConnected, server::managedBlock, 15000);
    }

    static long drain(Object connection, LongConsumer send, BooleanSupplier connected,
                      Consumer<BooleanSupplier> pump, long timeoutMillis) throws Exception {
        long started = System.nanoTime();
        try (InputFence.Request request = FENCE.begin(connection)) {
            send.accept(request.id);
            // managedBlock bypasses the normal tick budget while still running queued native input in order.
            request.await(pump, connected, timeoutMillis);
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    static void receive(Marker marker, Object connection, Consumer<Runnable> serverQueue) {
        FENCE.enqueue(connection, marker.id, serverQueue);
    }

    static void handle(Marker marker, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection() == NetworkDirection.PLAY_TO_SERVER)
            receive(marker, context.getNetworkManager(), task -> context.enqueueWork(task));
        context.setPacketHandled(true);
    }

    static final class Marker {
        final long id;
        Marker(long id) { this.id = id; }
        void encode(FriendlyByteBuf data) { data.writeLong(id); }
        static Marker decode(FriendlyByteBuf data) { return new Marker(data.readLong()); }
    }
}
