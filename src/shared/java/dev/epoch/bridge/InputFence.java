package dev.epoch.bridge;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Server-thread acknowledgement of a marker ordered after the client's last gameplay packet. */
public final class InputFence {
    private long sequence;
    private Request active;

    public Request begin(Object connection) throws IOException {
        if (connection == null || active != null) throw new IOException("Invalid or overlapping input fence");
        active = new Request(connection, ++sequence);
        return active;
    }

    /** Called on the network thread; acknowledgement must use the SAME queue as vanilla gameplay packets. */
    public void enqueue(Object connection, long id, Consumer<Runnable> serverQueue) {
        serverQueue.accept(() -> {
            Request request = active;
            if (request != null && request.connection == connection && request.id == id) {
                request.checkThread();
                request.received = true;
            }
        });
    }

    public final class Request implements AutoCloseable {
        public final long id;
        private final Object connection;
        private final Thread owner = Thread.currentThread();
        private boolean received;
        private Request(Object connection, long id) { this.connection = connection; this.id = id; }

        /** pump must process native server tasks even while normal world ticking is frozen. */
        public void await(Consumer<BooleanSupplier> pump, BooleanSupplier connected, long timeoutMillis) throws IOException {
            checkThread();
            if (active != this || timeoutMillis < 1) throw new IOException("Inactive input fence or invalid timeout");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            pump.accept(() -> received || !connected.getAsBoolean() || Thread.currentThread().isInterrupted()
                    || System.nanoTime() >= deadline);
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted before player input was saved");
            if (!connected.getAsBoolean()) throw new IOException("Player disconnected before input was saved");
            if (!received) throw new IOException("Timed out waiting for player input; snapshot was not exported");
        }

        private void checkThread() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Input acknowledgement requires the server thread");
        }

        @Override public void close() { checkThread(); if (active == this) active = null; }
    }
}
