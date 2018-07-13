package org.icij.datashare;

import net.codestory.http.WebServer;
import org.icij.datashare.mode.AbstractMode;
import org.icij.datashare.mode.ModeLocal;
import org.icij.datashare.mode.ModeNer;
import org.icij.datashare.mode.ModeProduction;

import java.util.Properties;

public class WebApp {

    public static void main(String[] args) {
        start(null);
    }

    public static void start(Properties properties) {
        AbstractMode mode;
        switch (Mode.valueOf(properties.getProperty("mode"))) {
            case NER:
                mode = new ModeNer(properties);
                break;
            case LOCAL:
                mode = new ModeLocal(properties);
                break;
            case PRODUCTION:
                mode = new ModeProduction(properties);
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