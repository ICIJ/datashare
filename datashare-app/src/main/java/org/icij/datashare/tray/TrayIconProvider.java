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
    private static final String TEMPLATE_ICON = "datashare-template.png";
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
        BufferedImage silhouette = loadSilhouette();
        if (silhouette == null) {
            return null;
        }
        int size = preferredSize > 0 ? preferredSize : defaultSize();
        return IconTinter.tint(silhouette, iconColor(), size);
    }

    protected BufferedImage loadSilhouette() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TEMPLATE_ICON)) {
            if (in == null) {
                LOGGER.warn("Tray silhouette '{}' not found on classpath", TEMPLATE_ICON);
                return null;
            }
            return ImageIO.read(in);
        } catch (IOException e) {
            LOGGER.warn("Could not read tray silhouette '{}'", TEMPLATE_ICON, e);
            return null;
        }
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
