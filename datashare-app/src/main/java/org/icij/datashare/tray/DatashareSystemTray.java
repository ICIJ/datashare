
package org.icij.datashare.tray;

import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import org.icij.datashare.mode.CommonMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URL;

public class DatashareSystemTray {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatashareSystemTray.class);

    private final SystemTray systemTray;
    private final CommonMode mode;

    private DatashareSystemTray(SystemTray systemTray, CommonMode mode) {
        this.systemTray = systemTray;
        this.mode = mode;
        configure();
    }

    public static DatashareSystemTray create(CommonMode mode) {
        SystemTray systemTray = SystemTray.get();

        if (systemTray == null) {
            LOGGER.error("SystemTray is NULL - not supported on this system");
            return null;
        }

        return new DatashareSystemTray(systemTray, mode);
    }

    private void configure() {
        systemTray.setTooltip("Datashare");
        loadIcon();
        configureMenu();
        LOGGER.info("SystemTray should appear in your system tray");
    }

    private void configureMenu() {
        Menu menu = systemTray.getMenu();
        menu.add(new MenuItem("Quit", e -> {
            LOGGER.info("Shutdown requested from system tray");
            System.exit(0);
        }));
    }

    private void loadIcon() {
        try {
            URL datashareIcon = DatashareSystemTray.class.getClassLoader().getResource("datashare.png");
            if (datashareIcon != null) {
                systemTray.setImage(datashareIcon);
                LOGGER.info("Custom tray icon loaded successfully");
            } else {
                LOGGER.warn("Tray icon not found in resources, using default");
                setDefaultIcon();
            }
        } catch (Exception e) {
            LOGGER.warn("Could not load custom tray icon, using default", e);
            setDefaultIcon();
        }
    }

    private void setDefaultIcon() {
        BufferedImage defaultImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = defaultImage.createGraphics();
        g2d.setColor(Color.PINK);
        g2d.fillRect(0, 0, 16, 16);
        g2d.dispose();
        systemTray.setImage(defaultImage);
    }
}
