package org.icij.datashare;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Double.parseDouble;
import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static org.icij.datashare.cli.DatashareCliOptions.BROWSER_OPEN_LINK_OPT;

import java.awt.Desktop;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import java.util.Properties;
import net.codestory.http.WebServer;
import org.icij.datashare.asynctasks.TaskAlreadyExists;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskSupplier;
import org.icij.datashare.asynctasks.TaskWorkerLoop;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_TASK_PROGRESS_INTERVAL_SECONDS;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_TASK_WORKERS;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_PROGRESS_INTERVAL_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_WORKERS_OPT;

public class WebApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebApp.class);

    static void start(CommonMode mode) throws Exception {
        new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(mode.createWebConfiguration())
                .start(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT_OPT)));

        if (mode.isEmbeddedAMQP()) {
            mode.createWorkers();
        }

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
                parseBoolean(mode.properties().getProperty(BROWSER_OPEN_LINK_OPT))) {
            waitForServerToBeUp(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT_OPT)));
            Desktop.getDesktop().browse(URI.create(new URI("http://localhost:") + mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT_OPT)));
        }
        requeueDatabaseBatchSearches(mode.get(BatchSearchRepository.class), mode.get(TaskManager.class));
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
            try {
                taskManager.startTask(batchSearchUuid, BatchSearchRunner.class, batchSearch.user, Map.of("batchRecord", new BatchSearchRecord(batchSearch)));
            } catch (TaskAlreadyExists e) {
                LOGGER.info("ignoring already started task <{}>", batchSearchUuid);
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
