
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

        DatashareSystemTray dataShareTray = new DatashareSystemTray(systemTray, mode);
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
        menu.add(new MenuItem("Open Browser", e -> openBrowser()));
        menu.add(new MenuItem("Quit", e -> quit()));
    }

    private void openBrowser() {
        String port = mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT_OPT);
        String url = "http://localhost:" + port;
        WebBrowserUtils.openBrowser(url);
    }

    private void quit() {
        LOGGER.info("Shutdown requested from system tray");
        System.exit(0);
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
