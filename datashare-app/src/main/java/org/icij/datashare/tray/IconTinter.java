package org.icij.datashare.tray;

import java.awt.Color;
import java.awt.image.BufferedImage;

public final class IconTinter {

    private IconTinter() {
    }

    /**
     * Recolor every pixel of {@code src} to {@code target}, preserving each pixel's
     * original alpha. The dimensions are left untouched: silhouettes are shipped
     * pre-rendered at their native sizes and handed to the tray as-is, so the OS does
     * any final scaling instead of us downscaling here.
     */
    public static BufferedImage tint(BufferedImage src, Color target) {
        int w = src.getWidth();
        int h = src.getHeight();
        int rgb = target.getRGB() & 0x00FFFFFF;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = (src.getRGB(x, y) >>> 24) & 0xFF;
                out.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        return out;
    }
}
