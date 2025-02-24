package org.icij.datashare.mode;

import java.io.IOException;
import org.icij.datashare.ExtensionService;
import org.icij.datashare.nlp.PythonNlpWorkerPool;
import org.icij.datashare.asynctasks.bus.amqp.QpidAmqpServer;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration;
import org.icij.datashare.text.indexing.elasticsearch.EsEmbeddedServer;

import java.util.Map;
import java.util.Properties;

public class EmbeddedMode extends LocalMode {
    public static int AMQP_PORT = 5672;
    EmbeddedMode(Properties properties) { super(properties);}
    public EmbeddedMode(Map<String, Object> properties) { super(properties);}

    @Override
    protected void configure() {
        String elasticsearchDataPath = propertiesProvider.get("elasticsearchDataPath").orElse("/home/datashare/es");
        addCloseable(new EsEmbeddedServer(ElasticsearchConfiguration.ES_CLUSTER_NAME, elasticsearchDataPath, elasticsearchDataPath, "9200").start());
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
}
