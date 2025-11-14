package org.icij.datashare.mode;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.kestra.cli.App;
import io.kestra.sdk.internal.ApiClient;
import io.kestra.sdk.internal.Configuration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.icij.datashare.ExtensionService;
import org.icij.datashare.asynctasks.bus.amqp.QpidAmqpServer;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.nlp.PythonNlpWorkerPool;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration;
import org.icij.datashare.text.indexing.elasticsearch.EsEmbeddedServer;

public class EmbeddedMode extends LocalMode {
    private static final String KESTRA_LOCAL_URL = "http://localhost:8080";
    public static int AMQP_PORT = 5672;

    private static final Path KESTRA_CONFIG_PATH = Path.of("~", ".datashare", "kestra-config.yml");
    private static final Path KESTRA_DB_PATH = Path.of("~", ".datashare", "kestra-db");

    EmbeddedMode(Properties properties) {
        super(properties);
    }

    public EmbeddedMode(Map<String, Object> properties) {
        super(properties);
    }

    @Override
    protected void configure() {
        String elasticsearchDataPath = propertiesProvider.get("elasticsearchDataPath").orElse("/home/datashare/es");
        addCloseable(new EsEmbeddedServer(ElasticsearchConfiguration.ES_CLUSTER_NAME, elasticsearchDataPath,
            elasticsearchDataPath, "9200").start());
        if (propertiesProvider.getProperties().contains(QueueType.AMQP.name())) {
            addCloseable(new QpidAmqpServer(AMQP_PORT).start());
            ExtensionService extensionService = new ExtensionService(propertiesProvider);
            bind(ExtensionService.class).toInstance(extensionService);
            boolean isSpacyInstalled = !extensionService
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
        KestraConfig config;
        try {
            config = new KestraConfig(KESTRA_CONFIG_PATH);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        configureKestra(config);
    }

    @Provides
    @Singleton
    ApiClient provideKestraClient() {
        ApiClient defaultClient = Configuration.getDefaultApiClient();
        defaultClient.setBasePath(KESTRA_LOCAL_URL);
        return defaultClient;
    }

    private void configureKestra(KestraConfig config) {
        // TODO: do we need to close it ??? The app doesn't seem to have handle to be close
        // TODO: pass all necessary args here like (the kestra server config and other stuff)
        String[] args = {
            "server", "standalone",
            "-c", config.path.toString()
        };
        executorService.submit(() -> App.main(args));
    }

    private class KestraConfig {
        public static final String KESTRA_CONFIG_RESOURCE_PATH = "kestra-config.yml";
        public final Path path;

        KestraConfig(Path targetPath) throws IOException {
            KESTRA_DB_PATH.toFile().mkdirs();
            this.path = targetPath;
            this.path.getParent().toFile().mkdirs();
            if (this.path.toFile().exists()) {
                Files.delete(this.path);
            }
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            try (InputStream inputStream = classLoader.getResourceAsStream(KESTRA_CONFIG_RESOURCE_PATH)) {
                Files.copy(Objects.requireNonNull(inputStream), this.path);
            }
        }
    }
}
