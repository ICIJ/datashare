package org.icij.datashare;

import net.codestory.http.WebServer;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.mode.CommonMode;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Properties;

import static java.lang.Integer.parseInt;

public class WebApp {

    public static void main(String[] args) throws IOException {
        start(new DatashareCli().parseArguments(args).properties);
    }

    static void start(Properties properties) throws IOException {
        CommonMode mode = CommonMode.create(properties);
        PluginService.createLinkToPlugins(Paths.get("app"), properties);
        new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(mode.createWebConfiguration())
                .start(parseInt(mode.properties().getProperty("tcpListenPort")));
    }

}
