package org.icij.datashare;

import net.codestory.http.WebServer;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.bus.amqp.QpidAmqpServer;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Properties;

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
        Runtime.getRuntime().addShutdownHook(close(mode));

        new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(mode.createWebConfiguration())
                .start(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
                parseBoolean(properties.getProperty(BROWSER_OPEN_LINK_OPT))) {
            waitForServerToBeUp(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));
            Desktop.getDesktop().browse(URI.create(new URI("http://localhost:") + mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));
        }
        requeueDatabaseBatchSearches(mode.get(BatchSearchRepository.class), mode.get(TaskManager.class));
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

    private static void requeueDatabaseBatchSearches(BatchSearchRepository repository, TaskManager taskManager) throws IOException {
        for (String batchSearchUuid: repository.getQueued()) {
            BatchSearch batchSearch = repository.get(batchSearchUuid);
            taskManager.startTask(batchSearchUuid, BatchSearchRunner.class, batchSearch.user);
        }
    }

    private static boolean isOpen(int port) {
        try (Socket ignored = new Socket("localhost", port)) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Thread close(CommonMode mode) {
        return new Thread(() -> {
            try {
                mode.close();
            } catch (IOException e) {
                LoggerFactory.getLogger(WebApp.class).error("Error closing web app", e);
            }
        });
    }
}
