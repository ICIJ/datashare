package org.icij.datashare.mode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.icij.datashare.ExtensionService;
import org.icij.datashare.OsArchDetector;
import org.icij.datashare.nlp.PythonNlpWorkerPool;
import org.icij.datashare.asynctasks.bus.amqp.QpidAmqpServer;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.process.Process;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

import static java.lang.String.format;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.cli.DatashareCliOptions.ELASTICSEARCH_DATA_PATH_OPT;

public class EmbeddedMode extends LocalMode {
    private static final Logger logger = LoggerFactory.getLogger(EmbeddedMode.class);
    public static int AMQP_PORT = 5672;
    EmbeddedMode(Properties properties) { super(properties);}
    public EmbeddedMode(Map<String, Object> properties) { super(properties);}

    @Override
    protected void configure() {
        String elasticsearchSettings = propertiesProvider.get(ELASTICSEARCH_SETTINGS_OPT).orElse(DEFAULT_ELASTICSEARCH_SETTINGS);
        String elasticsearchDir = propertiesProvider.get(ELASTICSEARCH_PATH_OPT).orElse(DEFAULT_ELASTICSEARCH_PATH);
        String elasticsearchDataPath = propertiesProvider.get(ELASTICSEARCH_DATA_PATH_OPT).orElseThrow(
                () -> new IllegalArgumentException(
                        format("Missing required option %s.", ELASTICSEARCH_DATA_PATH_OPT))
        );
        createDefaultSettingsFileIfNeeded(elasticsearchSettings, elasticsearchDataPath);
        List<String> args = buildElasticsearchArgs(Path.of(elasticsearchSettings));
        String elasticsearchScript = new OsArchDetector().isWindows() ? "elasticsearch.bat" : "elasticsearch";
        args.add(0, format("%s/current/bin/%s", elasticsearchDir, elasticsearchScript));
        logger.info("Starting Elasticsearch from local install {} within a new JVM.", elasticsearchDir);
        new Process(elasticsearchDir,
                "elasticsearch",
                args.toArray(new String[0]),
                9200).start();
        if (propertiesProvider.getProperties().contains(QueueType.AMQP.name())) {
            addCloseable(new QpidAmqpServer(AMQP_PORT).start());
            ExtensionService extensionService = new ExtensionService(propertiesProvider);
            bind(ExtensionService.class).toInstance(extensionService);
            boolean isSpacyInstalled = ! extensionService
            .listInstalled("datashare-extension-nlp-spacy.*")
            .isEmpty();
            if (isSpacyInstalled) {
                PythonNlpWorkerPool workerPool = new PythonNlpWorkerPool(extensionService, propertiesProvider);
                try {
                    addCloseable(workerPool.start());
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException("failed to start Python worker pull !", e);
                }
            }
        }
        Properties properties = new Properties();
        properties.put(ElasticsearchConfiguration.INDEX_ADDRESS_PROP, "http://localhost:9200");
        propertiesProvider.overrideWith(properties);
        super.configure();
    }

    private void createDefaultSettingsFileIfNeeded(String settingsPath, String dataPath) {
        Path settingsFile = Path.of(settingsPath);
        if (!settingsFileExists(settingsFile)) {
            createDefaultSettingsFile(settingsFile, dataPath);
        }
    }

    private boolean settingsFileExists(Path settingsFile) {
        return Files.exists(settingsFile);
    }

    protected void createDefaultSettingsFile(Path settingsFile, String dataPath) {
        try {
            Path backupsDir = settingsFile.getParent().resolve("backups");
            String defaultContent = """
                    path.data: "%s"
                    path.repo: "%s"
                    xpack.security.enabled: false
                    indices.id_field_data.enabled: true
                    indices.query.bool.max_clause_count: 16384
                    cluster.routing.allocation.disk.watermark.low: 90%%
                    cluster.routing.allocation.disk.watermark.high: 99%%
                    cluster.routing.allocation.disk.watermark.flood_stage: 100%%
                    """.formatted(dataPath, backupsDir);
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, defaultContent);
            logger.info("Created default elasticsearch settings file at {}", settingsFile);
        } catch (IOException e) {
            throw new RuntimeException(format("failed to create default elasticsearch settings file at %s", settingsFile), e);
        }
    }

    protected static List<String> buildElasticsearchArgs(Path settingsFile) {
        List<String> args = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(settingsFile);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.contains(":")) {
                    String[] parts = line.split(":", 2);
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^\\s*\"|\\s*\"$", "");
                    args.add(format("-E%s=%s", key, value));
                }
            }
            logger.info("loaded {} elasticsearch settings from {}", args.size(), settingsFile);
        } catch (IOException e) {
            throw new RuntimeException(format("failed to read elasticsearch settings from %s", settingsFile), e);
        }
        return args;
    }
}
