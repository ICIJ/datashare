package org.icij.datashare;

import net.codestory.http.WebServer;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.com.bus.amqp.QpidAmqpServer;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.TaskFactory;

import java.awt.*;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static org.icij.datashare.cli.DatashareCliOptions.BROWSER_OPEN_LINK_OPT;

public class WebApp {

    public static void main(String[] args) throws Exception {
        start(new DatashareCli().parseArguments(args).properties);
    }

    static void start(Properties properties) throws Exception {
        if (shouldStartQpid(properties)) {
            // before creating mode because AmqpInterlocutor will try to connect the broker
            new QpidAmqpServer(5672).start();
        }
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
                parseBoolean(properties.getProperty(BROWSER_OPEN_LINK_OPT))) {
            waitForServerToBeUp(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));
            Desktop.getDesktop().browse(URI.create(new URI("http://localhost:")+mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));
        }
        if (mode.getMode() == Mode.LOCAL || mode.getMode() == Mode.EMBEDDED) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            executor.submit(mode.get(TaskFactory.class).createBatchDownloadLoop());
            executor.submit(mode.get(TaskFactory.class).createBatchSearchLoop());
        }
        webServerThread.join();
    }

    private static boolean shouldStartQpid(Properties properties) {
        return CommonMode.getMode(properties) == Mode.EMBEDDED && properties.containsValue(QueueType.AMQP.name());
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
