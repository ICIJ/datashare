package org.icij.datashare.tray;

import dorkbox.systemTray.SystemTray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.icij.datashare.tray.SystemThemeDetector.Theme;

/**
 * Resolves the system-tray icon image per platform and theme.
 *
 * <p>Icons are shipped pre-rendered (from the SVG sources under {@code src/main/icons}) as three
 * colour variants at a ladder of sizes — {@code tray/<variant>-<size>.png} on the classpath — so
 * no recolouring or resizing happens at runtime; the right file is loaded and handed to the tray
 * as-is. The variants are:
 * <ul>
 *   <li>{@code black} — the macOS template image (the OS recolours it) and the Linux/Windows light theme;</li>
 *   <li>{@code white} — the Linux/Windows dark theme;</li>
 *   <li>{@code color} — the full-colour logo, used when the theme cannot be determined; it is
 *       visible on either a light or dark panel without having to guess.</li>
 * </ul>
 *
 * <p>On macOS, {@link #prepare()} MUST be called before {@link dorkbox.systemTray.SystemTray#get()}:
 * it forces the AWT-backed tray and enables template images so the OS recolors the black
 * silhouette to match the menu bar. Setting these after the tray has initialised has no effect.</p>
 */
public class TrayIconProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrayIconProvider.class);
    /** Pre-rendered square sizes shipped for every variant, ascending. */
    static final int[] AVAILABLE_SIZES = {16, 24, 32, 48, 64, 96, 128};
    private static final int MAC_ICON_SIZE = 22;
    static final int DEFAULT_ICON_SIZE = 16;

    /** Pre-rendered colour variants; {@link #fileName} is the {@code tray/<name>-<size>.png} stem. */
    enum Variant {
        BLACK("black"), WHITE("white"), COLOR("color");
        final String fileName;
        Variant(String fileName) { this.fileName = fileName; }
    }

    private final OsFamily os;
    private final SystemThemeDetector detector;

    public static TrayIconProvider forCurrentPlatform() {
        OsFamily os = OsFamily.current();
        return new TrayIconProvider(os, new SystemThemeDetector(os));
    }

    TrayIconProvider(OsFamily os, SystemThemeDetector detector) {
        this.os = os;
        this.detector = detector;
    }

    /**
     * macOS only: force the AWT-backed tray and enable template images so the OS
     * recolors the black silhouette to match the menu bar. Must run before
     * {@link SystemTray#get()}.
     */
    public void prepare() {
        if (os == OsFamily.MAC) {
            SystemTray.FORCE_TRAY_TYPE = SystemTray.TrayType.Awt;
            System.setProperty("apple.awt.enableTemplateImages", "true");
        }
    }

    /**
     * Returns the tray image for the live system theme, at its native pre-rendered size
     * (the OS/tray does any final scaling). Detecting the theme may spawn a short-lived
     * subprocess, so callers on a latency-sensitive path should prefer
     * {@link #loadInitialTrayImage(int)} and refresh off-thread. Null if the asset is unavailable.
     */
    public Image loadTrayImage(int preferredSize) {
        return loadTrayImage(preferredSize, currentTheme());
    }

    /**
     * Returns the tray image for an already-resolved {@code theme}, avoiding any theme detection.
     * Used by the off-thread refresh loop, which detects once via {@link #currentTheme()}.
     */
    public Image loadTrayImage(int preferredSize, Theme theme) {
        return loadVariant(variantFor(theme), preferredSize);
    }

    /**
     * Returns an icon that can be shown immediately without detecting the system theme
     * (no subprocess), so tray creation never blocks on startup. On macOS this is the
     * black template image the OS recolors; elsewhere it is the full-colour logo, legible
     * on any panel, which the refresh loop later replaces with the theme-accurate icon.
     * Null if the asset is unavailable.
     */
    public Image loadInitialTrayImage(int preferredSize) {
        return loadVariant(os == OsFamily.MAC ? Variant.BLACK : Variant.COLOR, preferredSize);
    }

    /** Whether the icon colour depends on a runtime-detectable theme that can change while running. */
    public boolean tracksSystemTheme() {
        return os == OsFamily.LINUX || os == OsFamily.WINDOWS;
    }

    /** Detects the current system theme (macOS recolors via template image, so reports UNKNOWN). */
    public Theme currentTheme() {
        return os == OsFamily.MAC ? Theme.UNKNOWN : detector.detect();
    }

    private Variant variantFor(Theme theme) {
        if (os == OsFamily.MAC) {
            return Variant.BLACK; // template image; the OS recolors it
        }
        switch (theme) {
            case LIGHT: return Variant.BLACK;
            case DARK:  return Variant.WHITE;
            default:    return Variant.COLOR; // theme unknown: the colour logo reads on any panel
        }
    }

    /**
     * Loads the pre-rendered {@code variant} at the size best suited to {@code targetSize}: the
     * smallest shipped size that is &ge; the target, so when the tray resizes it down to the exact
     * indicator size it only ever downscales (a crisp pre-rendered source). When the target exceeds
     * every shipped size the largest is used and the tray will have to upscale it (logged below),
     * which only happens on very high-DPI displays beyond the ladder's top rung.
     */
    protected BufferedImage loadVariant(Variant variant, int targetSize) {
        int size = nearestAvailableSize(targetSize > 0 ? targetSize : defaultSize());
        String resource = "tray/" + variant.fileName + "-" + size + ".png";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("Tray icon '{}' not found on classpath", resource);
                return null;
            }
            return ImageIO.read(in);
        } catch (IOException e) {
            LOGGER.warn("Could not read tray icon '{}'", resource, e);
            return null;
        }
    }

    /** Smallest shipped size &ge; {@code targetSize}, or the largest shipped size if none qualifies. */
    static int nearestAvailableSize(int targetSize) {
        for (int size : AVAILABLE_SIZES) {
            if (size >= targetSize) {
                return size;
            }
        }
        int largest = AVAILABLE_SIZES[AVAILABLE_SIZES.length - 1];
        LOGGER.debug("Requested tray size {} exceeds the largest pre-rendered icon ({}px); "
                + "the tray will upscale it", targetSize, largest);
        return largest;
    }

    private int defaultSize() {
        return os == OsFamily.MAC ? MAC_ICON_SIZE : DEFAULT_ICON_SIZE;
    }
}
