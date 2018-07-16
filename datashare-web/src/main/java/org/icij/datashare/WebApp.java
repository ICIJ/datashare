package org.icij.datashare;

import net.codestory.http.WebServer;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.mode.LocalMode;
import org.icij.datashare.mode.NerMode;
import org.icij.datashare.mode.ProductionMode;

import java.util.Properties;

public class WebApp {

    public static void main(String[] args) {
        start(null);
    }

    public static void start(Properties properties) {
        CommonMode mode;
        switch (Mode.valueOf(properties.getProperty("mode"))) {
            case NER:
                mode = new NerMode(properties);
                break;
            case LOCAL:
                mode = new LocalMode(properties);
                break;
            case PRODUCTION:
                mode = new ProductionMode(properties);
                break;
            default:
                throw new IllegalStateException("unknown mode : " + properties.getProperty("mode"));
        }
        new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(mode.createWebConfiguration()).start();
    }
}