package dev.epoch.legacy;

import java.io.File;
import java.lang.instrument.Instrumentation;

/** Loads the stock LWJGL library before any display exists, then guards its own Win32 calls. */
public final class EpochLegacyAgent {
    private EpochLegacyAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        if (!"1".equals(System.getenv("EPOCH_HIDDEN_START"))) return;
        if (!System.getProperty("os.name", "").startsWith("Windows"))
            throw new IllegalStateException("Epoch legacy window hosting requires Windows");
        String nativePath = System.getProperty("epoch.legacyNative");
        if (nativePath == null || !new File(nativePath).isAbsolute())
            throw new IllegalStateException("epoch.legacyNative must name an absolute DLL path");
        Class.forName("org.lwjgl.Sys", true, ClassLoader.getSystemClassLoader());
        System.load(new File(nativePath).getCanonicalPath());
        int hooks = install();
        // These functions are imported by both 2.9.1 and 2.9.4. Abort before creating a display
        // if a different LWJGL binary cannot provide hidden startup and child input.
        int required = 31 | 2048 | 4096;
        if ((hooks & required) != required)
            throw new IllegalStateException("Incomplete LWJGL2 window hooks: " + hooks);
        System.setProperty("epoch.legacyHiddenInstalled", Integer.toString(hooks));
        System.err.println("[Epoch] LWJGL2 hidden child startup and mouse hooks installed (hooks=" + hooks + ")");
    }

    private static native int install();
    static native long[] diagnostics();
}
