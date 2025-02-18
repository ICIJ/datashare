package org.icij.datashare;

import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;

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

        if (cli.isWebServer()) {
            WebApp.start(cli.properties);
        } else if (cli.mode() == Mode.TASK_WORKER) {
            TaskWorkerApp.start(cli.properties);
        } else {
            CliApp.start(cli.properties);
        }
        LOGGER.info("exiting main");
    }
}
