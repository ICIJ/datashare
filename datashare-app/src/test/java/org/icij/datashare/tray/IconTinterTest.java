package org.icij.datashare.tray;

import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
    public void test_output_is_square_of_requested_size() {
        BufferedImage out = IconTinter.tint(opaque(10, 10), Color.WHITE, 16);
        assertEquals(16, out.getWidth());
        assertEquals(16, out.getHeight());
    }

    @Test
    public void test_recolors_opaque_pixels_to_target_and_keeps_alpha() {
        // square source fills the whole square canvas -> center is solid
        BufferedImage out = IconTinter.tint(opaque(10, 10), Color.WHITE, 16);
        int center = out.getRGB(8, 8);
        assertEquals("alpha preserved", 0xFF, (center >>> 24) & 0xFF);
        assertEquals("red -> white", 0xFF, (center >> 16) & 0xFF);
        assertEquals("green -> white", 0xFF, (center >> 8) & 0xFF);
        assertEquals("blue -> white", 0xFF, center & 0xFF);
    }

    @Test
    public void test_black_target_produces_black_pixels() {
        BufferedImage out = IconTinter.tint(opaque(10, 10), Color.BLACK, 16);
        int center = out.getRGB(8, 8);
        assertEquals(0xFF, (center >>> 24) & 0xFF);
        assertEquals(0x00, (center >> 16) & 0xFF);
        assertEquals(0x00, (center >> 8) & 0xFF);
        assertEquals(0x00, center & 0xFF);
    }

    @Test
    public void test_portrait_source_is_centered_with_transparent_padding() {
        // portrait 5x10 -> fit into 16 -> 8 wide x 16 tall, centered (left pad ~4px)
        BufferedImage out = IconTinter.tint(opaque(5, 10), Color.WHITE, 16);
        // corners are padding -> fully transparent
        assertEquals("top-left transparent", 0x00, (out.getRGB(0, 0) >>> 24) & 0xFF);
        assertEquals("top-right transparent", 0x00, (out.getRGB(15, 0) >>> 24) & 0xFF);
        // a column at the horizontal centre, mid-height, is opaque content
        int mid = out.getRGB(8, 8);
        assertTrue("centre content is opaque", ((mid >>> 24) & 0xFF) > 0);
    }

    @Test
    public void test_large_downscale_averages_instead_of_aliasing() {
        // 256x256 of 8px-tall alternating opaque/transparent bands (50% coverage).
        // A naive single bilinear pass to 8px samples a 2x2 neighbourhood, lands in
        // one band phase and aliases to ~all-opaque or ~all-transparent. High-quality
        // (progressive) downscaling averages the bands, so every pixel lands near 50%.
        BufferedImage src = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) {
            int alpha = ((y / 8) % 2 == 0) ? 0xFF : 0x00;
            for (int x = 0; x < 256; x++) {
                src.setRGB(x, y, alpha << 24);
            }
        }

        BufferedImage out = IconTinter.tint(src, Color.WHITE, 8);

        long sum = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                sum += (out.getRGB(x, y) >>> 24) & 0xFF;
            }
        }
        double meanAlpha = sum / 64.0;
        assertTrue("downscale should average ~50% coverage, not alias; mean alpha=" + meanAlpha,
                meanAlpha > 96 && meanAlpha < 160);
    }
}
