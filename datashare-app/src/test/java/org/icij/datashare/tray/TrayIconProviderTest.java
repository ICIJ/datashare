package org.icij.datashare.tray;

import dorkbox.systemTray.SystemTray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.awt.Image;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TrayIconProviderTest {

    private static SystemThemeDetector fixedDetector(OsFamily os, SystemThemeDetector.Theme theme) {
        SystemThemeDetector.CommandRunner runner;
        switch (theme) {
            case DARK:
                runner = (t, cmd) -> os == OsFamily.WINDOWS ? "0x0" : "'prefer-dark'";
                break;
            case LIGHT:
                runner = (t, cmd) -> os == OsFamily.WINDOWS ? "0x1" : "'prefer-light'";
                break;
            default:
                runner = (t, cmd) -> "garbage";
        }
        return new SystemThemeDetector(os, runner);
    }

    @Before
    public void resetTrayState() {
        System.clearProperty("apple.awt.enableTemplateImages");
        SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.AutoDetect;
    }

    @After
    public void clearTrayState() {
        System.clearProperty("apple.awt.enableTemplateImages");
        SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.AutoDetect;
    }

    private static int alpha(int argb) { return (argb >>> 24) & 0xFF; }
    private static boolean isWhite(int argb) {
        return ((argb >> 16) & 0xFF) == 0xFF && ((argb >> 8) & 0xFF) == 0xFF && (argb & 0xFF) == 0xFF;
    }
    private static boolean isBlack(int argb) {
        return ((argb >> 16) & 0xFF) == 0x00 && ((argb >> 8) & 0xFF) == 0x00 && (argb & 0xFF) == 0x00;
    }

    private static int firstOpaquePixel(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (alpha(img.getRGB(x, y)) == 0xFF) {
                    return img.getRGB(x, y);
                }
            }
        }
        throw new AssertionError("no fully opaque pixel found");
    }

    @Test
    public void test_linux_dark_theme_yields_white_icon() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX,
                fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.DARK));
        BufferedImage img = (BufferedImage) provider.loadTrayImage(16);
        assertNotNull(img);
        assertEquals(16, img.getWidth());
        assertTrue("dark theme -> white silhouette", isWhite(firstOpaquePixel(img)));
    }

    @Test
    public void test_linux_light_theme_yields_black_icon() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX,
                fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.LIGHT));
        BufferedImage img = (BufferedImage) provider.loadTrayImage(16);
        assertTrue("light theme -> black silhouette", isBlack(firstOpaquePixel(img)));
    }

    @Test
    public void test_unknown_theme_defaults_to_white() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX,
                fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.UNKNOWN));
        BufferedImage img = (BufferedImage) provider.loadTrayImage(16);
        assertTrue("unknown -> white silhouette", isWhite(firstOpaquePixel(img)));
    }

    @Test
    public void test_macos_yields_black_template_and_uses_default_size() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.MAC,
                fixedDetector(OsFamily.MAC, SystemThemeDetector.Theme.LIGHT));
        BufferedImage img = (BufferedImage) provider.loadTrayImage(0); // 0 -> platform default
        assertEquals(22, img.getWidth());
        assertTrue("macOS template is black", isBlack(firstOpaquePixel(img)));
    }

    @Test
    public void test_prepare_sets_macos_template_flags() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.MAC,
                fixedDetector(OsFamily.MAC, SystemThemeDetector.Theme.LIGHT));
        provider.prepare();
        assertEquals("true", System.getProperty("apple.awt.enableTemplateImages"));
        assertEquals(SystemTray.TrayType.Awt, SystemTray.FORCE_TRAY_TYPE);
    }

    @Test
    public void test_prepare_is_noop_on_linux() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX,
                fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.DARK));
        provider.prepare();
        assertNull(System.getProperty("apple.awt.enableTemplateImages"));
        assertEquals(SystemTray.TrayType.AutoDetect, SystemTray.FORCE_TRAY_TYPE);
    }

    @Test
    public void test_returns_null_when_asset_missing() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX,
                fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.DARK)) {
            @Override
            protected BufferedImage loadSilhouette() {
                return null;
            }
        };
        assertNull(provider.loadTrayImage(16));
    }
}
