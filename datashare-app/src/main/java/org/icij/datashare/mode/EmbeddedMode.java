package org.icij.datashare.mode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.icij.datashare.ExtensionService;
import org.icij.datashare.nlp.PythonNlpWorkerPool;
import org.icij.datashare.asynctasks.bus.amqp.QpidAmqpServer;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration;
import org.icij.datashare.text.indexing.elasticsearch.EsEmbeddedServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_ELASTICSEARCH_SETTINGS;
import static org.icij.datashare.cli.DatashareCliOptions.ELASTICSEARCH_SETTINGS_OPT;

public class EmbeddedMode extends LocalMode {
    private static final Logger logger = LoggerFactory.getLogger(EmbeddedMode.class);
    public static int AMQP_PORT = 5672;
    EmbeddedMode(Properties properties) { super(properties);}
    public EmbeddedMode(Map<String, Object> properties) { super(properties);}

    @Override
    protected void configure() {
        String elasticsearchDataPath = propertiesProvider.get("elasticsearchDataPath").orElse("/home/datashare/es");
        String elasticsearchSettings = propertiesProvider.get(ELASTICSEARCH_SETTINGS_OPT).orElse(DEFAULT_ELASTICSEARCH_SETTINGS);
        createDefaultSettingsFileIfNeeded(elasticsearchSettings);
        addCloseable(new EsEmbeddedServer(ElasticsearchConfiguration.ES_CLUSTER_NAME, elasticsearchDataPath, elasticsearchDataPath, "9200", "9300", elasticsearchSettings).start());
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

    private void createDefaultSettingsFileIfNeeded(String settingsPath) {
        Path settingsFile = Path.of(settingsPath);
        if (!settingsFileExists(settingsFile)) {
            createDefaultSettingsFile(settingsFile);
        }
    }

    private boolean settingsFileExists(Path settingsFile) {
        return Files.exists(settingsFile);
    }

    private void createDefaultSettingsFile(Path settingsFile) {
        try {
            Path backupsDir = settingsFile.getParent().resolve("backups");
            String defaultContent = String.format("""
                    path.repo:
                      - "%s"
                    """, backupsDir);
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, defaultContent);
            logger.info("Created default elasticsearch settings file at {}", settingsFile);
        } catch (IOException e) {
            logger.warn("Failed to create default elasticsearch settings file at {}: {}", settingsFile, e.getMessage());
        }
    }
}
