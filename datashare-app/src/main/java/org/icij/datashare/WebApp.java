package org.icij.datashare;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static org.icij.datashare.cli.DatashareCliOptions.BROWSER_OPEN_LINK_OPT;

import java.awt.Desktop;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import java.util.Properties;
import net.codestory.http.WebServer;
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
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_TASK_WORKERS;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_WORKERS_OPT;

public class WebApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebApp.class);

    public static void main(String[] args) throws Exception {
        start(new DatashareCli().parseArguments(args).properties);
    }

    static void start(Properties properties) throws Exception {
        int taskWorkersNb = parseInt((String) ofNullable(properties.get(TASK_WORKERS_OPT)).orElse(DEFAULT_TASK_WORKERS));

        CommonMode mode = CommonMode.create(properties);
        Runtime.getRuntime().addShutdownHook(close(mode));

        new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(mode.createWebConfiguration())
                .start(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));

        if (isEmbeddedAMQP(properties, taskWorkersNb)) {
            ExecutorService executorService = Executors.newFixedThreadPool(taskWorkersNb);
            List<TaskWorkerLoop> workers = IntStream.range(0, taskWorkersNb).mapToObj(i -> new TaskWorkerLoop(mode.get(DatashareTaskFactory.class), mode.get(TaskSupplier.class))).toList();
            workers.forEach(executorService::submit);
        }

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
                parseBoolean(properties.getProperty(BROWSER_OPEN_LINK_OPT))) {
            waitForServerToBeUp(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));
            Desktop.getDesktop().browse(URI.create(new URI("http://localhost:") + mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT)));
        }
        requeueDatabaseBatchSearches(mode.get(BatchSearchRepository.class), mode.get(TaskManager.class));
    }

    private static boolean isEmbeddedAMQP(Properties properties, int taskWorkersNb) {
        return (CommonMode.getMode(properties) == Mode.EMBEDDED || CommonMode.getMode(properties) == Mode.LOCAL)
                && properties.containsValue(QueueType.AMQP.name())
                && taskWorkersNb > 0;
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
            taskManager.startTask(batchSearchUuid, BatchSearchRunner.class, batchSearch.user, Map.of("batchRecord", new BatchSearchRecord(batchSearch)));
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
                LOGGER.error("Error closing web app", e);
            }
        });
    }
}
