package org.icij.datashare;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static org.icij.datashare.cli.DatashareCliOptions.BROWSER_OPEN_LINK_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PARALLELISM_OPT;
import static org.icij.datashare.utils.ProcessHandler.dumpPid;
import static org.icij.datashare.utils.ProcessHandler.findPidPaths;
import static org.icij.datashare.utils.ProcessHandler.isProcessRunning;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import net.codestory.http.WebServer;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.bus.amqp.QpidAmqpServer;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.mode.EmbeddedMode;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebApp {
    private static final int AMQP_PORT = 5672;
    private static final Logger LOGGER = LoggerFactory.getLogger(Indexer.class);

    public static void main(String[] args) throws Exception {
        start(new DatashareCli().parseArguments(args).properties);
    }

    static void start(Properties properties) throws Exception {
        boolean isEmbeddedAMQP = isEmbeddedAMQP(properties);
        if (isEmbeddedAMQP) {
            // before creating mode because AmqpInterlocutor will try to connect the broker
            new QpidAmqpServer(AMQP_PORT).start();
        }
        CommonMode mode = CommonMode.create(properties);
        Process nlpWorkerProcess;
        Path nlpWorkersPidPath;
        boolean startNlpWorker = isEmbeddedAMQP && !mode.get(ExtensionService.class)
            .listInstalled("datashare-spacy-worker.*")
            .isEmpty();
        if (startNlpWorker) {
            // TODO: we miss a callback to kill the process pool
            nlpWorkerProcess = buildNlpWorkersProcess((EmbeddedMode) mode)
                .redirectErrorStream(true).inheritIO().start();
            nlpWorkersPidPath = Files.createTempFile("datashare-spacy-worker-", ".pid");
            dumpPid(nlpWorkersPidPath.toFile(), nlpWorkerProcess.pid());
            LOGGER.debug("dumping worker pid to " + nlpWorkersPidPath);
        }

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
        requeueDatabaseBatchSearches(mode.get(BatchSearchRepository.class), mode.get(TaskManager.class));
        webServerThread.join();
    }

    private static boolean isEmbeddedAMQP(Properties properties) {
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

    protected static ProcessBuilder buildNlpWorkersProcess(ExecutableExtensionHelper extensionHelper, int nWorkers) throws IOException {
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));
        for (Path p: findPidPaths("regex:" + extensionHelper.getPidFilePattern(), tmpRoot)) {
            if (isProcessRunning(p, 1, TimeUnit.SECONDS)) {
                    String pid;
                    try {
                        pid = Files.readAllLines(p).get(0);
                    } catch (IOException e) {
                        throw new RuntimeException("failed to read pid from " + p);
                    }
                    String msg = "found phantom worker running in process " + pid
                        + ", kill this process before restarting datashare !";
                    throw new RuntimeException(msg);
                }
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
        }
        // If not we start them
        Path workerConfigPath = dumpNlpWorkerConfig();
        return extensionHelper.buildProcess(workerConfigPath.toString(), "-n", String.valueOf(nWorkers));
    }

    private static ProcessBuilder buildNlpWorkersProcess(EmbeddedMode mode) throws IOException {
        ExecutableExtensionHelper nlpExtHelper = new ExecutableExtensionHelper(
            mode.get(ExtensionService.class),  "datashare-nlp-spacy"
        );
        int nWorkers = mode.get(PropertiesProvider.class).get(NLP_PARALLELISM_OPT).map(Integer::parseInt).orElse(1);
        return buildNlpWorkersProcess(nlpExtHelper, nWorkers);
    }

    private static Path dumpNlpWorkerConfig() throws IOException {
        Map<String, String> workerConfig = Map.of(
            "type", "amqp",
            "rabbitmq_host", "localhost",
            "rabbitmq_port", String.valueOf(AMQP_PORT)
        );
        Path workerConfigPath = Files.createTempFile("datashare-spacy-worker-config-", ".json");
        File tempFile = workerConfigPath.toFile();
        // Write the JSON object to the temporary file
        JsonObjectMapper.MAPPER.writeValue(tempFile, workerConfig);
        return workerConfigPath;
    }
}
