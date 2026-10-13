package dev.epoch;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounds vanilla's uncompressed Anvil backlog before another chunk is serialized. */
public final class NativeSaveQueue {
    static final int MAX_PENDING = 8;
    private static final AtomicInteger peak = new AtomicInteger();
    private static final AtomicLong waitNanos = new AtomicLong();
    private NativeSaveQueue() {}

    static java.util.Map<String, Object> diagnostics() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("pendingLimit", MAX_PENDING); result.put("peakObservedPending", peak.get());
        result.put("waitMillis", TimeUnit.NANOSECONDS.toMillis(waitNanos.get()));
        Runtime runtime = Runtime.getRuntime();
        result.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        result.put("heapMaxBytes", runtime.maxMemory());
        return result;
    }

    public static void awaitCapacity(List<?> pending, Object lock) {
        awaitCapacity(pending, lock, TimeUnit.SECONDS.toNanos(30));
    }

    static void awaitCapacity(List<?> pending, Object lock, long timeoutNanos) {
        long deadline = System.nanoTime() + timeoutNanos;
        synchronized (lock) {
            peak.accumulateAndGet(pending.size(), Math::max);
            while (pending.size() >= MAX_PENDING) {
                if (System.nanoTime() >= deadline)
                    throw new SaveStalled("Epoch native chunk saving stalled; refusing to grow the NBT queue", null);
                long started = System.nanoTime();
                try {
                    // Vanilla removes entries under this same lock but does not notify.
                    // Release it while waiting; only its existing IO worker writes regions.
                    lock.wait(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SaveStalled("Epoch native chunk saving interrupted", interrupted);
                } finally { waitNanos.addAndGet(System.nanoTime() - started); }
            }
        }
    }

    // Vanilla catches Exceptions and marks the chunk saved anyway. Stop the world instead.
    static final class SaveStalled extends Error {
        SaveStalled(String message, Throwable cause) { super(message, cause); }
    }
}
