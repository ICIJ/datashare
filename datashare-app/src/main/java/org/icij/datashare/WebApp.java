package org.icij.datashare;

import com.google.inject.Injector;
import net.codestory.http.WebServer;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchSearchLoop;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManager;

import java.awt.*;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Properties;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static org.icij.datashare.cli.DatashareCliOptions.OPEN_LINK;

public class WebApp {

    public static void main(String[] args) throws Exception {
        start(new DatashareCli().parseArguments(args).properties);
    }

    static void start(Properties properties) throws Exception {
        CommonMode mode = CommonMode.create(properties);

        Thread webServerThread = new Thread(() ->
                new WebServer()
                        .withThreadCount(10)
                        .withSelectThreads(2)
                        .withWebSocketThreads(1)
                        .configure(mode.createWebConfiguration())
                        .start(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)))
        );
        webServerThread.start();
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
                parseBoolean(properties.getProperty(OPEN_LINK))) {
            waitForServerToBeUp(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));
            Desktop.getDesktop().browse(URI.create(new URI("http://localhost:")+mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));
        }
        if (mode.getMode() == Mode.LOCAL || mode.getMode() == Mode.EMBEDDED) {
            BatchSearchLoop batchSearchLoop = mode.get(TaskFactory.class).createBatchSearchLoop();
            TaskManager taskManager = mode.get(TaskManager.class);
            taskManager.startTask(batchSearchLoop::run);
        }
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
