package org.icij.datashare;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static org.icij.datashare.cli.DatashareCliOptions.OPEN_LINK;

import java.awt.Desktop;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Properties;
import net.codestory.http.WebServer;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.com.PulsarStatusHandler;
import org.icij.datashare.mode.CommonMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebApp {

    private static final Logger logger = LoggerFactory.getLogger(CliApp.class);


    public static void main(String[] args) throws Exception {
        start(new DatashareCli().parseArguments(args).properties);
    }

    static void start(Properties properties) throws Exception {
//        // TODO: remove this, needed for attaching a debugger to the dependency injection
//        Thread.sleep(1000 * 10);
        CommonMode mode = CommonMode.create(properties);
        logger.info("after mode creation start");
        Thread webServerThread = new Thread(() ->
            new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(mode.createWebConfiguration())
                .start(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)))
        );
        logger.info("starting status handler");
        Thread statusHandlerThread = new Thread(mode.get(PulsarStatusHandler.class));
        webServerThread.start();
        logger.info("webserver started");
        statusHandlerThread.start();
        logger.info("handler started");
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
            parseBoolean(properties.getProperty(OPEN_LINK))) {
            waitForServerToBeUp(
                parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));
            Desktop.getDesktop().browse(URI.create(new URI("http://localhost:") +
                mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));
        }
        // TODO: put his back
//        if (mode.getMode() == Mode.LOCAL || mode.getMode() == Mode.EMBEDDED) {
//            BatchSearchLoop batchSearchLoop = mode.get(TaskFactory.class).createBatchSearchLoop();
//            TaskManager taskManager = mode.get(TaskManager.class);
//            taskManager.startTask(batchSearchLoop::run);
//        }
        webServerThread.join();
    }

    private static void waitForServerToBeUp(int tcpListenPort) throws InterruptedException {
        for (int nbTries = 0; nbTries < 60; nbTries++) {
            if (isOpen(tcpListenPort)) {
                return;
            } else {
                Thread.sleep(500);
            }
        }
    }

    private static boolean isOpen(int port) {
        try (Socket ignored = new Socket("localhost", port)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
