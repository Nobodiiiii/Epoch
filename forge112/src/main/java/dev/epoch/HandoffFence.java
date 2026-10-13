package dev.epoch;

import dev.epoch.bridge.InputFence;
import io.netty.buffer.ByteBuf;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;

/** Freeze the client first, then wait for an ordered acknowledgement on the server task queue. */
public final class HandoffFence {
    private static final InputFence FENCE = new InputFence();
    private static SimpleNetworkWrapper network;

    static void register() {
        network = NetworkRegistry.INSTANCE.newSimpleChannel("epoch_handoff");
        network.registerMessage(Handler.class, Marker.class, 0, Side.SERVER);
    }

    static long drain(MinecraftServer server, NetHandlerPlayServer connection) throws Exception {
        return drain(connection.netManager,
                id -> Minecraft.getMinecraft().addScheduledTask(() -> network.sendToServer(new Marker(id))),
                connection.netManager::isChannelOpen, done -> {
                    while (!done.getAsBoolean()) {
                        try { Handoff.drainTasks(server); }
                        catch (Exception error) { throw new IllegalStateException("Cannot drain player input", error); }
                        java.util.concurrent.locks.LockSupport.parkNanos(100000L);
                    }
                }, 15000);
    }

    static long drain(Object connection, LongConsumer send, BooleanSupplier connected,
                      Consumer<BooleanSupplier> pump, long timeoutMillis) throws Exception {
        long started = System.nanoTime();
        try (InputFence.Request request = FENCE.begin(connection)) {
            send.accept(request.id);
            request.await(pump, connected, timeoutMillis);
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    static void receive(Marker marker, Object connection, Consumer<Runnable> serverQueue) {
        FENCE.enqueue(connection, marker.id, serverQueue);
    }

    public static final class Marker implements IMessage {
        long id;
        public Marker() {}
        Marker(long id) { this.id = id; }
        public void fromBytes(ByteBuf data) { id = data.readLong(); }
        public void toBytes(ByteBuf data) { data.writeLong(id); }
    }

    public static final class Handler implements IMessageHandler<Marker, IMessage> {
        public IMessage onMessage(Marker marker, MessageContext context) {
            NetHandlerPlayServer connection = context.getServerHandler();
            // Unlike 1.7, this callback runs on Netty. Receiving the marker alone is NOT acknowledgement.
            receive(marker, connection.netManager, task -> connection.player.getServerWorld().addScheduledTask(task));
            return null;
        }
    }
}
