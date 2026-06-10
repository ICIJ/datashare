package org.icij.datashare.tray;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public final class IconTinter {

    private IconTinter() {
    }

    /**
     * Recolor every pixel of {@code src} to {@code target} (keeping each pixel's
     * original alpha), then fit the result into a transparent {@code sizePx x sizePx}
     * square, centered, preserving aspect ratio.
     */
    public static BufferedImage tint(BufferedImage src, Color target, int sizePx) {
        BufferedImage recolored = recolor(src, target);
        return fitSquare(recolored, sizePx);
    }

    private static BufferedImage recolor(BufferedImage src, Color target) {
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

    private static BufferedImage fitSquare(BufferedImage src, int sizePx) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = (double) sizePx / Math.max(w, h);
        int scaledW = Math.max(1, (int) Math.round(w * scale));
        int scaledH = Math.max(1, (int) Math.round(h * scale));
        int offsetX = (sizePx - scaledW) / 2;
        int offsetY = (sizePx - scaledH) / 2;

        BufferedImage canvas = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = canvas.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g2d.drawImage(src, offsetX, offsetY, scaledW, scaledH, null);
        g2d.dispose();
        return canvas;
    }
}
