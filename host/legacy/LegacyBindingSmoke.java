package dev.epoch.legacy;

import java.util.Arrays;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;

/** Exercises stock LWJGL 2 window creation and swap; no Minecraft or visible parent is used. */
public final class LegacyBindingSmoke {
    public static void main(String[] args) throws Exception {
        if (System.getProperty("epoch.legacyHiddenInstalled") == null)
            throw new AssertionError("Agent not installed");
        long parent = 0;
        try {
            Display.setDisplayMode(new DisplayMode(96, 96));
            Display.setTitle("Epoch hidden binding smoke");
            Display.create();
            GL11.glClearColor(0.1f, 0.2f, 0.3f, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            Display.update();
            long[] before = EpochLegacyAgent.diagnostics();
            if (before[0] < 31 || before[1] == 0 || before[2] == 0 || before[3] == 0
                    || before[4] != 0 || before[5] != 0 || before[6] != 1)
                throw new AssertionError("Display escaped startup guard: " + Arrays.toString(before));
            parent = EpochLegacyAgent.adoptForSmoke(before[3]);
            if (parent == 0) throw new AssertionError("Could not adopt smoke child");
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            Display.update();
            long[] after = EpochLegacyAgent.diagnostics();
            if (after[4] != 0 || after[5] == 0 || after[6] != 0)
                throw new AssertionError("Adopted child not ready: " + Arrays.toString(after));
            System.out.println("PASS LWJGL " + org.lwjgl.Sys.getVersion()
                    + " OpenGL=" + GL11.glGetString(GL11.GL_VERSION)
                    + " before=" + Arrays.toString(before) + " after=" + Arrays.toString(after));
        } finally {
            Display.destroy();
            EpochLegacyAgent.releaseSmokeParent(parent);
        }
    }
}
