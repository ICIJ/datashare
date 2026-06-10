
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

import static java.lang.String.format;

public class DatashareSystemTray implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatashareSystemTray.class);
    private static final int DEFAULT_ICON_SIZE = 16;

    private final SystemTray systemTray;
    private final TrayActions actions;
    private final TrayIconProvider iconProvider;

    DatashareSystemTray(SystemTray systemTray, TrayActions actions, TrayIconProvider iconProvider) {
        this.systemTray = systemTray;
        this.actions = actions;
        this.iconProvider = iconProvider;
        configure();
    }

    public static DatashareSystemTray create(String port) {
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
        Image image = iconProvider.loadTrayImage(systemTray.getMenuImageSize());
        if (image != null) {
            systemTray.setImage(image);
            return;
        }
        LOGGER.warn("Tray icon could not be prepared, using default");
        setDefaultIcon();
    }

    private void setDefaultIcon() {
        BufferedImage defaultImage = new BufferedImage(DEFAULT_ICON_SIZE, DEFAULT_ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = defaultImage.createGraphics();
        g2d.setColor(Color.PINK);
        g2d.fillRect(0, 0, DEFAULT_ICON_SIZE, DEFAULT_ICON_SIZE);
        g2d.dispose();
        systemTray.setImage(defaultImage);
    }

    @Override
    public void close() throws IOException {
        systemTray.shutdown();
    }
}
