package org.icij.datashare.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;

public class WebBrowserUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebBrowserUtils.class);

    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                LOGGER.info("Browser opened at {}", url);
                return;
            }
        } catch (Throwable ex) {
            LOGGER.debug("Failed to open browser", ex);
        }
        LOGGER.info("Please open your browser at: {}", url);
    }

    public static void openBrowser(int port, boolean shouldOpenBrowser) throws InterruptedException {
        if (shouldOpenBrowser) {
            waitForServerToBeUp(port);
            openBrowser("http://localhost:" + port);
        }
    }

    private static void waitForServerToBeUp(int tcpListenPort) throws InterruptedException {
        for (int nbTries = 0; nbTries < 60; nbTries++) {
            if (isPortOpen(tcpListenPort)) {
                return;
            } else {
                Thread.sleep(500);
            }
        }
    }

    private static boolean isPortOpen(int port) {
        try (Socket ignored = new Socket("localhost", port)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}