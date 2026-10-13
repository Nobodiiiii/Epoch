package dev.epoch;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.*;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import net.minecraft.network.NetHandlerPlayServer;

/** A marker in the same 1.7 receive queue as inventory clicks and block placements. */
public final class HandoffFence {
    static final String CHANNEL = "epoch_handoff";
    private static SimpleNetworkWrapper network;
    private static long sequence;
    private static Pending pending;

    private static final class Pending {
        final NetHandlerPlayServer connection;
        final long id;
        boolean received;
        Pending(NetHandlerPlayServer connection, long id) { this.connection = connection; this.id = id; }
    }

    static void register() {
        network = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        network.registerMessage(Handler.class, Marker.class, 0, Side.SERVER);
    }

    // Called on the client thread only, after captureFrozen has closed the inventory and frozen input.
    static void send(long id) { network.sendToServer(new Marker(id)); }

    static int drain(NetHandlerPlayServer connection) throws Exception {
        return drain(connection, id -> ClientHooks.execute(() -> send(id)), 15000);
    }

    static int drain(NetHandlerPlayServer connection, LongConsumer send, long timeoutMillis) throws Exception {
        if (pending != null) throw new IOException("An input fence is already pending");
        Pending request = new Pending(connection, ++sequence);
        pending = request;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        int passes = 0;
        try {
            send.accept(request.id);
            while (!request.received) {
                if (!connection.netManager.isChannelOpen()) throw new IOException("Player disconnected before input was saved");
                if (System.nanoTime() >= deadline) throw new IOException("Timed out waiting for player input; snapshot was not exported");
                // 1.7 drains at most 1001 queued packets per pass. A single networkTick is insufficient.
                connection.netManager.processReceivedPackets();
                passes++;
                if (!request.received) Thread.sleep(1);
            }
            return passes;
        } finally { pending = null; }
    }

    public static final class Marker implements IMessage {
        long id;
        public Marker() {}
        Marker(long id) { this.id = id; }
        public void fromBytes(ByteBuf data) { id = data.readLong(); }
        public void toBytes(ByteBuf data) { data.writeLong(id); }
    }

    public static final class Handler implements IMessageHandler<Marker, IMessage> {
        public IMessage onMessage(Marker message, MessageContext context) {
            // FMLProxyPacket is non-priority in 1.7: this runs on the server thread after earlier vanilla packets.
            Pending request = pending;
            if (request != null && request.connection == context.getServerHandler() && request.id == message.id)
                request.received = true;
            return null;
        }
    }
}
