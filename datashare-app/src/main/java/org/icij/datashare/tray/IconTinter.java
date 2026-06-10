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

        BufferedImage scaled = highQualityScale(src, scaledW, scaledH);

        BufferedImage canvas = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = canvas.createGraphics();
        g2d.drawImage(scaled, offsetX, offsetY, null);
        g2d.dispose();
        return canvas;
    }

    /**
     * Downscale to {@code targetW x targetH} preserving quality. A single bilinear
     * pass only samples a 2x2 neighbourhood, so reducing a large source (e.g. the
     * 519x900 silhouette) straight to a ~16-24px icon badly undersamples and looks
     * jagged. Halving repeatedly until within 2x of the target averages enough
     * source pixels to stay smooth, then a final high-quality step hits the exact size.
     */
    private static BufferedImage highQualityScale(BufferedImage src, int targetW, int targetH) {
        BufferedImage current = src;
        int w = src.getWidth();
        int h = src.getHeight();
        while (w > targetW * 2 || h > targetH * 2) {
            w = Math.max(targetW, w / 2);
            h = Math.max(targetH, h / 2);
            current = scaleStep(current, w, h, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        return scaleStep(current, targetW, targetH, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage scaleStep(BufferedImage src, int w, int h, Object interpolation) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = out.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(src, 0, 0, w, h, null);
        g2d.dispose();
        return out;
    }
}
