package org.icij.datashare.tray;

import dorkbox.systemTray.SystemTray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Resolves the system-tray icon image per platform and theme.
 *
 * <p>On macOS, {@link #prepare()} MUST be called before {@link dorkbox.systemTray.SystemTray#get()}:
 * it forces the AWT-backed tray and enables template images so the OS recolors the black
 * silhouette to match the menu bar. Setting these after the tray has initialised has no effect.
 * On Linux/Windows {@code prepare()} is a no-op and the icon colour is chosen once from the
 * detected theme.</p>
 */
public class TrayIconProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrayIconProvider.class);
    /** Pre-rendered square silhouettes shipped as resources, ascending. */
    static final int[] AVAILABLE_SIZES = {16, 24, 32, 48, 64};
    private static final int MAC_ICON_SIZE = 22;
    private static final int DEFAULT_ICON_SIZE = 16;

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

    /** Returns the tray image, or null if the silhouette asset is unavailable. */
    public Image loadTrayImage(int preferredSize) {
        int size = preferredSize > 0 ? preferredSize : defaultSize();
        BufferedImage silhouette = loadSilhouette(size);
        if (silhouette == null) {
            return null;
        }
        return IconTinter.tint(silhouette, iconColor(), size);
    }

    /**
     * Loads the pre-rendered silhouette best suited to {@code targetSize}: the smallest
     * shipped size that is &ge; the target (so it is only ever downscaled, never upscaled),
     * falling back to the largest when the target exceeds every shipped size.
     */
    protected BufferedImage loadSilhouette(int targetSize) {
        String resource = "datashare-template-" + nearestAvailableSize(targetSize) + ".png";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("Tray silhouette '{}' not found on classpath", resource);
                return null;
            }
            return ImageIO.read(in);
        } catch (IOException e) {
            LOGGER.warn("Could not read tray silhouette '{}'", resource, e);
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
        return AVAILABLE_SIZES[AVAILABLE_SIZES.length - 1];
    }

    private int defaultSize() {
        return os == OsFamily.MAC ? MAC_ICON_SIZE : DEFAULT_ICON_SIZE;
    }

    private Color iconColor() {
        if (os == OsFamily.MAC) {
            return Color.BLACK; // OS recolors via the template image
        }
        return detector.detect() == SystemThemeDetector.Theme.LIGHT ? Color.BLACK : Color.WHITE;
    }
}
