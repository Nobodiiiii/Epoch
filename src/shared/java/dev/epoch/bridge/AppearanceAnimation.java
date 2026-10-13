package dev.epoch.bridge;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/** One animated pixel buffer per material, shared by every block and item using it. */
public final class AppearanceAnimation {
    public static final int FRAMES_PER_SECOND = 12;
    public final int width, height;
    /** Binary alpha (including leaf holes) uses cutout, never translucent sorting. */
    public final boolean translucent;
    private final int[] original;
    private long lastFrame = Long.MIN_VALUE;

    public AppearanceAnimation(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null || image.getWidth() > 4096 || image.getHeight() > 4096)
            throw new IOException("Invalid appearance animation image");
        width = image.getWidth(); height = image.getHeight();
        original = image.getRGB(0, 0, width, height, null, 0, width);
        boolean partial = false;
        for (int pixel : original) { int alpha = pixel >>> 24; if (alpha != 0 && alpha != 255) partial = true; }
        translucent = partial;
    }

    public static long frame() { return System.nanoTime() / (1_000_000_000L / FRAMES_PER_SECOND); }

    /** No allocations; repeated references to the same material in a frame do no pixel work. */
    public boolean update(int[] target, long frame, boolean abgr) {
        if (target.length != original.length) throw new IllegalArgumentException("Wrong pixel buffer size");
        if (frame == lastFrame) return false;
        lastFrame = frame;
        double time = frame / (double)FRAMES_PER_SECOND;
        for (int y = 0; y < height; y++) {
            float noise = Appearance.glitch(time, y / (double)height);
            for (int x = 0; x < width; x++) {
                int index = y * width + x, pixel = original[index];
                int red = (pixel >>> 16) & 255, green = (pixel >>> 8) & 255, blue = pixel & 255;
                if (noise > 0) {
                    red = Math.min(255, (int)(red * .4f + noise * .35f * 255));
                    green = Math.min(255, (int)(green * .4f + noise * 255));
                    blue = Math.min(255, (int)(blue * .4f + noise * 255));
                }
                // Never fill a leaf hole or change genuine partial transparency.
                target[index] = (pixel & 0xff000000) | (abgr ? blue << 16 | green << 8 | red : red << 16 | green << 8 | blue);
            }
        }
        return true;
    }
}
