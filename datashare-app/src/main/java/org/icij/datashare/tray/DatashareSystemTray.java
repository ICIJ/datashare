
package org.icij.datashare.tray;

import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.utils.WebBrowserUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.net.URL;

public class DatashareSystemTray implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatashareSystemTray.class);
    private static final String DATASHARE_ICON_FILENAME = "datashare.png";
    private static final int DEFAULT_ICON_SIZE = 16;

    private final SystemTray systemTray;
    private final TrayActions actions;


    DatashareSystemTray(SystemTray systemTray, TrayActions actions) {
        this.systemTray = systemTray;
        this.actions = actions;
        configure();
    }

    public static DatashareSystemTray create(CommonMode mode) {
        SystemTray systemTray = null;
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment();
            systemTray = SystemTray.get();
        } catch (Throwable e) {
            LOGGER.warn("Could not initialize SystemTray: {}", e.getMessage());
        }

        if (systemTray == null) {
            LOGGER.error("SystemTray is not supported on this system");
            return null;
        }

        TrayActions actions = new TrayActions() {
            @Override
            public void openBrowser() {
                String port = mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT_OPT);
                String url = "http://localhost:" + port;
                WebBrowserUtils.openBrowser(url);
            }

            @Override
            public void quit() {
                LOGGER.info("Shutdown requested from system tray");
                System.exit(0);
            }
        };

        DatashareSystemTray dataShareTray = new DatashareSystemTray(systemTray, actions);
        mode.addCloseable(dataShareTray);
        return dataShareTray;
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
        try {
            URL datashareIcon = DatashareSystemTray.class.getClassLoader().getResource(DATASHARE_ICON_FILENAME);
            if (datashareIcon != null) {
                systemTray.setImage(datashareIcon);
                return;
            }
            LOGGER.warn("Tray icon not found in resources, using default");
        } catch (Exception e) {
            LOGGER.warn("Could not load Datashare tray icon, using default", e);
        }
        setDefaultIcon();
    }

    private void setDefaultIcon() {
        BufferedImage defaultImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
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
