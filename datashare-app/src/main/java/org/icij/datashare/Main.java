package org.icij.datashare;

import dorkbox.systemTray.SystemTray;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tray.DatashareSystemTray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.Charset;

public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    public static void main(String[] args) throws Exception {
        DatashareCli cli = new DatashareCli().parseArguments(args);
        LOGGER.info("Running datashare {}", cli.isWebServer() ? "web server" : "");
        LOGGER.info("JVM version {}", System.getProperty("java.version"));
        LOGGER.info("JVM charset encoding {}", Charset.defaultCharset());
        Level logLevel = Level.toLevel(cli.properties.getProperty("logLevel"));
        LOGGER.info("Log level set to {}", logLevel);

        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(logLevel);

        CommonMode mode = CommonMode.create(cli.properties);
        Runtime.getRuntime().addShutdownHook(mode.closeThread());

        if (cli.isWebServer()) {
            WebApp.start(mode);
            DatashareSystemTray.create(mode);
        } else if (cli.mode() == Mode.TASK_WORKER) {
            TaskWorkerApp.start(mode);
        } else {
            CliApp.start(cli.properties);
        }
        LOGGER.info("exiting main");
    }


    private static void setDefaultIcon(SystemTray systemTray) {
        BufferedImage defaultImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = defaultImage.createGraphics();
        g2d.setColor(Color.BLUE);
        g2d.fillRect(0, 0, 16, 16);
        g2d.dispose();
        systemTray.setImage(defaultImage);
    }
}