package org.icij.datashare;

import net.codestory.http.WebServer;
import org.icij.datashare.mode.CommonMode;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static java.lang.Integer.parseInt;
import static org.icij.datashare.PropertiesProvider.PLUGINS_DIR;

public class WebApp {

    public static void main(String[] args) throws IOException { start(null);}

    static void start(Properties properties) throws IOException {
        CommonMode mode = CommonMode.create(properties);
        createLinkToPlugins(Paths.get("app"), properties);
        new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(mode.createWebConfiguration())
                .start(parseInt(mode.properties().getProperty("tcpListenPort")));
    }

    public static void createLinkToPlugins(Path pathToApp, Properties properties) throws IOException {
        try {
            if (properties.containsKey(PLUGINS_DIR)) {
                Files.createSymbolicLink(pathToApp.resolve("plugins"), Paths.get(properties.getProperty(PLUGINS_DIR)));
            }
        } catch (FileAlreadyExistsException e) {
            // nothing to do
        }
    }
}
