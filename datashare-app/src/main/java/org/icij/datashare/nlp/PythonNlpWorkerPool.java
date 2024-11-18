package org.icij.datashare.nlp;

import static org.icij.datashare.cli.DatashareCliOptions.NLP_PARALLELISM_OPT;
import static org.icij.datashare.mode.EmbeddedMode.AMQP_PORT;
import static org.icij.datashare.utils.ProcessHandler.dumpPid;
import static org.icij.datashare.utils.ProcessHandler.findPidPaths;
import static org.icij.datashare.utils.ProcessHandler.isProcessRunning;
import static org.icij.datashare.utils.ProcessHandler.killProcessById;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.icij.datashare.ExecutableExtensionHelper;
import org.icij.datashare.ExtensionService;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class PythonNlpWorkerPool implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PythonNlpWorkerPool.class);

    private final ExtensionService extensionService;
    private final int nWorkers;
    private Process workerProcess;
    protected Path pidPath;

    @Inject
    public PythonNlpWorkerPool(ExtensionService extensionService, PropertiesProvider propertiesProvider) {
        this.extensionService = extensionService;
        nWorkers = propertiesProvider.get(NLP_PARALLELISM_OPT).map(Integer::parseInt).orElse(1);
    }

    public PythonNlpWorkerPool start() throws IOException, InterruptedException {
        workerProcess = buildProcess().redirectErrorStream(true).inheritIO().start();
        pidPath = Files.createTempFile("datashare-extension-nlp-spacy-", ".pid");
        dumpPid(pidPath.toFile(), workerProcess.pid());
        LOGGER.debug("dumping worker pid to " + pidPath);
        return this;
    }

    protected ProcessBuilder buildProcess() throws IOException, InterruptedException {
        ExecutableExtensionHelper extensionHelper = new ExecutableExtensionHelper(
            extensionService, "datashare-extension-nlp-spacy"
        );
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));
        for (Path p : findPidPaths("regex:" + extensionHelper.getPidFilePattern(), tmpRoot)) {
            if (isProcessRunning(p, 1, TimeUnit.SECONDS)) {
                String pid = Files.readAllLines(p).get(0);
                String msg = "found phantom worker running in process " + pid
                    + ", kill this process before restarting datashare !";
                throw new RuntimeException(msg);
            }
            Files.deleteIfExists(p);
        }
        // If not we start them
        Path workerConfigPath = dumpNlpWorkerConfig();
        return extensionHelper.buildProcess(workerConfigPath.toString(), "-n", String.valueOf(nWorkers));
    }

    private static Path dumpNlpWorkerConfig() throws IOException {
        Map<String, String> workerConfig = Map.of(
            "type", "amqp",
            "rabbitmq_host", "localhost",
            "rabbitmq_port", String.valueOf(AMQP_PORT),
            "rabbitmq_user", "admin",
            "rabbitmq_password", "admin"
        );
        Path workerConfigPath = Files.createTempFile("datashare-extension-nlp-spacy-config-", ".json");
        File tempFile = workerConfigPath.toFile();
        // Write the JSON object to the temporary file
        JsonObjectMapper.MAPPER.writeValue(tempFile, workerConfig);
        return workerConfigPath;
    }

    @Override
    public void close() throws IOException {
        if (workerProcess != null && workerProcess.isAlive()) {
            killProcessById(workerProcess.pid());
        }
        if (pidPath != null && pidPath.toFile().exists()) {
            Files.delete(pidPath);
        }
    }
}
