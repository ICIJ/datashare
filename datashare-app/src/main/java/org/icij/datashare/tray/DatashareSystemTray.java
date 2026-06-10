package org.icij.datashare.tray;

import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import org.icij.datashare.utils.WebBrowserUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;
import static org.icij.datashare.tray.SystemThemeDetector.Theme;

public class DatashareSystemTray implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatashareSystemTray.class);
    private static final long THEME_POLL_SECONDS = 30;

    private final SystemTray systemTray;
    private final TrayActions actions;
    private final TrayIconProvider iconProvider;
    private ScheduledExecutorService themeWatcher;
    private volatile Theme lastAppliedTheme;

    DatashareSystemTray(SystemTray systemTray, TrayActions actions, TrayIconProvider iconProvider) {
        this.systemTray = systemTray;
        this.actions = actions;
        this.iconProvider = iconProvider;
        configure();
    }

    public static DatashareSystemTray create(String port) {
        if (GraphicsEnvironment.isHeadless()) {
            LOGGER.info("Headless environment, system tray disabled");
            return null; // bail out before prepare() mutates JVM-global AWT/tray state
        }
        TrayIconProvider iconProvider = TrayIconProvider.forCurrentPlatform();
        iconProvider.prepare(); // macOS: force AWT tray + template images BEFORE SystemTray.get()
        SystemTray systemTray = createSystemTray();
        if (systemTray == null) {
            return null;
        }
        return new DatashareSystemTray(systemTray, new TrayActions() {
            @Override
            public void openBrowser() {
                WebBrowserUtils.openBrowser(format("http://localhost:%s", port));
            }

            @Override
            public void quit() {
                LOGGER.info("Shutdown requested from system tray");
                System.exit(0);
            }
        }, iconProvider);
    }

    @Nullable
    private static SystemTray createSystemTray() {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment();
            return SystemTray.get();
        } catch (Throwable e) {
            LOGGER.warn("SystemTray is not supported on this system", e);
            return null;
        }
    }

    private void configure() {
        systemTray.setTooltip("Datashare");
        loadIcon();
        configureMenu();
    }

    private void configureMenu() {
        Menu menu = systemTray.getMenu();
        menu.add(new MenuItem("Open Browser", e -> actions.openBrowser()));
        menu.add(new MenuItem("Quit", e -> actions.quit()));
    }

    private void loadIcon() {
        // Size to the tray icon size (not the menu-row icon size): dorkbox re-resizes
        // whatever we hand it up to getTrayImageSize(), so providing a smaller image
        // would force an upscale and blur the icon.
        //
        // Use the detection-free initial icon here so tray creation never blocks on a
        // theme-detection subprocess; the watcher below refines it off the startup thread.
        applyIcon(iconProvider.loadInitialTrayImage(systemTray.getTrayImageSize()));
        if (iconProvider.tracksSystemTheme()) {
            lastAppliedTheme = Theme.UNKNOWN; // the initial icon is the outlined (unknown) rendering
            startThemeWatcher();
        }
    }

    private void startThemeWatcher() {
        themeWatcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tray-theme-watcher");
            thread.setDaemon(true);
            return thread;
        });
        // First run (delay 0) resolves the real theme off the startup thread; later runs
        // pick up the user toggling light/dark while Datashare is running.
        themeWatcher.scheduleWithFixedDelay(this::refreshThemeIcon, 0, THEME_POLL_SECONDS, TimeUnit.SECONDS);
    }

    void refreshThemeIcon() {
        try {
            Theme theme = iconProvider.currentTheme();
            if (theme == lastAppliedTheme) {
                return; // unchanged: skip a redundant re-encode/setImage
            }
            Image image = iconProvider.loadTrayImage(systemTray.getTrayImageSize(), theme);
            if (image != null) {
                systemTray.setImage(image);
                lastAppliedTheme = theme;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not refresh tray icon for system theme change", e);
        }
    }

    private void applyIcon(Image image) {
        try {
            if (image != null) {
                systemTray.setImage(image);
                return;
            }
            LOGGER.warn("Tray icon could not be prepared, using default");
        } catch (Exception e) {
            LOGGER.warn("Could not set Datashare tray icon, using default", e);
        }
        setDefaultIcon();
    }

    private void setDefaultIcon() {
        try {
            int size = TrayIconProvider.DEFAULT_ICON_SIZE;
            BufferedImage defaultImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = defaultImage.createGraphics();
            graphics.setColor(Color.PINK);
            graphics.fillRect(0, 0, size, size);
            graphics.dispose();
            systemTray.setImage(defaultImage);
        } catch (Exception e) {
            LOGGER.warn("Could not set default tray icon", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (themeWatcher != null) {
            themeWatcher.shutdownNow();
        }
        systemTray.shutdown();
    }
}
