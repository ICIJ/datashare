package org.icij.datashare;

import net.codestory.http.WebServer;
import org.icij.datashare.mode.CommonMode;

import java.util.Properties;

import static java.lang.Integer.parseInt;

public class WebApp {

    public static void main(String[] args) {
        start(null);
    }

    static void start(Properties properties) {
        CommonMode mode = CommonMode.create(properties);
        new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(mode.createWebConfiguration())
                .start(parseInt(properties.getProperty("tcpListenPort")));
    }
}
