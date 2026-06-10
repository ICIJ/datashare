package org.icij.datashare.tray;

import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;

public class IconTinterTest {

    private static BufferedImage opaque(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, 0xFF000000); // opaque black
            }
        }
        return img;
    }

    @Test
    public void test_output_keeps_source_dimensions() {
        // recolor only: the pre-rendered silhouette is handed over at its native size,
        // never resized here (the OS/tray does any final scaling).
        BufferedImage out = IconTinter.tint(opaque(24, 24), Color.WHITE);
        assertEquals(24, out.getWidth());
        assertEquals(24, out.getHeight());
    }

    @Test
    public void test_recolors_opaque_pixels_to_target_and_keeps_alpha() {
        BufferedImage out = IconTinter.tint(opaque(10, 10), Color.WHITE);
        int center = out.getRGB(5, 5);
        assertEquals("alpha preserved", 0xFF, (center >>> 24) & 0xFF);
        assertEquals("red -> white", 0xFF, (center >> 16) & 0xFF);
        assertEquals("green -> white", 0xFF, (center >> 8) & 0xFF);
        assertEquals("blue -> white", 0xFF, center & 0xFF);
    }

    @Test
    public void test_black_target_produces_black_pixels() {
        BufferedImage out = IconTinter.tint(opaque(10, 10), Color.BLACK);
        int center = out.getRGB(5, 5);
        assertEquals(0xFF, (center >>> 24) & 0xFF);
        assertEquals(0x00, (center >> 16) & 0xFF);
        assertEquals(0x00, (center >> 8) & 0xFF);
        assertEquals(0x00, center & 0xFF);
    }

    @Test
    public void test_transparent_pixels_stay_transparent() {
        BufferedImage src = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, 0x00000000); // fully transparent
        BufferedImage out = IconTinter.tint(src, Color.WHITE);
        assertEquals("alpha 0 stays 0", 0x00, (out.getRGB(0, 0) >>> 24) & 0xFF);
    }
}
