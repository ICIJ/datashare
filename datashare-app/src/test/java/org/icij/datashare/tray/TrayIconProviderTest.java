package org.icij.datashare.tray;

import dorkbox.systemTray.SystemTray;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;

import java.awt.Image;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Rule
    public final ExternalResource trayState = new ExternalResource() {
        @Override
        protected void before() {
            reset();
        }
        @Override
        protected void after() {
            reset();
        }
        private void reset() {
            System.clearProperty("apple.awt.enableTemplateImages");
            SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.AutoDetect;
        }
    };

    private static int alpha(int argb) { return (argb >>> 24) & 0xFF; }
    private static boolean isWhite(int argb) {
        return ((argb >> 16) & 0xFF) == 0xFF && ((argb >> 8) & 0xFF) == 0xFF && (argb & 0xFF) == 0xFF;
    }
    private static boolean isBlack(int argb) {
        return ((argb >> 16) & 0xFF) == 0x00 && ((argb >> 8) & 0xFF) == 0x00 && (argb & 0xFF) == 0x00;
    }
    /** A saturated (non-grey) pixel: clearly part of the colour logo, not the mono silhouettes. */
    private static boolean isColorful(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max - min > 40;
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

    private interface PixelMatch { boolean matches(int argb); }

    private static boolean hasOpaquePixel(BufferedImage img, PixelMatch match) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                if (alpha(argb) == 0xFF && match.matches(argb)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static SystemThemeDetector throwingDetector(OsFamily os) {
        return new SystemThemeDetector(os, (timeout, cmd) -> { throw new java.io.IOException("unavailable"); });
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
    public void test_unknown_theme_yields_colour_logo_visible_on_any_background() {
        // Detection fails (e.g. non-GNOME desktop / gsettings absent) -> UNKNOWN. A single
        // mono colour would be invisible on a same-coloured panel, so the full-colour logo
        // (which reads on either light or dark) is used instead.
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX, throwingDetector(OsFamily.LINUX));
        BufferedImage img = (BufferedImage) provider.loadTrayImage(16);
        assertNotNull(img);
        assertEquals(16, img.getWidth());
        assertTrue("unknown -> colour logo", hasOpaquePixel(img, TrayIconProviderTest::isColorful));
    }

    @Test
    public void test_initial_icon_needs_no_theme_detection() {
        // A detector that would blow up if consulted; the initial icon must not call it.
        TrayIconProvider provider = new TrayIconProvider(OsFamily.LINUX, throwingDetector(OsFamily.LINUX)) {
            @Override
            public SystemThemeDetector.Theme currentTheme() {
                throw new AssertionError("initial icon must not detect the theme");
            }
        };
        BufferedImage img = (BufferedImage) provider.loadInitialTrayImage(16);
        assertNotNull(img);
        // colour logo (any-background) on Linux, with no detection
        assertTrue("colour logo present", hasOpaquePixel(img, TrayIconProviderTest::isColorful));
    }

    @Test
    public void test_tracks_system_theme_only_on_linux_and_windows() {
        assertTrue(new TrayIconProvider(OsFamily.LINUX, fixedDetector(OsFamily.LINUX, SystemThemeDetector.Theme.DARK)).tracksSystemTheme());
        assertTrue(new TrayIconProvider(OsFamily.WINDOWS, fixedDetector(OsFamily.WINDOWS, SystemThemeDetector.Theme.DARK)).tracksSystemTheme());
        assertFalse(new TrayIconProvider(OsFamily.MAC, fixedDetector(OsFamily.MAC, SystemThemeDetector.Theme.LIGHT)).tracksSystemTheme());
    }

    @Test
    public void test_macos_initial_icon_is_black_template_without_detection() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.MAC, throwingDetector(OsFamily.MAC));
        BufferedImage img = (BufferedImage) provider.loadInitialTrayImage(0);
        assertTrue("macOS template is black", isBlack(firstOpaquePixel(img)));
    }

    @Test
    public void test_macos_yields_black_template_and_uses_default_size() {
        TrayIconProvider provider = new TrayIconProvider(OsFamily.MAC,
                fixedDetector(OsFamily.MAC, SystemThemeDetector.Theme.LIGHT));
        BufferedImage img = (BufferedImage) provider.loadTrayImage(0); // 0 -> platform default (22)
        // handed over at its native ladder size (nearest >= 22), not resized here
        assertEquals(24, img.getWidth());
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
            protected BufferedImage loadVariant(Variant variant, int targetSize) {
                return null;
            }
        };
        assertNull(provider.loadTrayImage(16));
    }

    @Test
    public void test_nearest_available_size_picks_smallest_not_smaller_than_target() {
        assertEquals(16, TrayIconProvider.nearestAvailableSize(16));
        assertEquals(24, TrayIconProvider.nearestAvailableSize(17)); // round up, never down
        assertEquals(24, TrayIconProvider.nearestAvailableSize(24));
        assertEquals(48, TrayIconProvider.nearestAvailableSize(40));
        assertEquals(64, TrayIconProvider.nearestAvailableSize(64));
        assertEquals(96, TrayIconProvider.nearestAvailableSize(80));   // HiDPI rungs
        assertEquals(128, TrayIconProvider.nearestAvailableSize(128));
        assertEquals(128, TrayIconProvider.nearestAvailableSize(256)); // beyond ladder -> largest
    }
}
