package org.icij.datashare.mode;

import org.icij.datashare.asynctasks.bus.amqp.QpidAmqpServer;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.cli.QueueType;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration;
import org.icij.datashare.text.indexing.elasticsearch.EsEmbeddedServer;

import java.util.Map;
import java.util.Properties;

public class EmbeddedMode extends LocalMode {
    EmbeddedMode(Properties properties) { super(properties);}
    public EmbeddedMode(Map<String, Object> properties) { super(properties);}

    @Override
    protected void configure() {
        String elasticsearchDataPath = propertiesProvider.get("elasticsearchDataPath").orElse("/home/datashare/es");
        addCloseable(new EsEmbeddedServer(ElasticsearchConfiguration.ES_CLUSTER_NAME, elasticsearchDataPath, elasticsearchDataPath, "9200").start());
        if (propertiesProvider.getProperties().contains(QueueType.AMQP.name())) {
            addCloseable(new QpidAmqpServer(5672).start());
        }
        Properties properties = new Properties();
        properties.put(ElasticsearchConfiguration.INDEX_ADDRESS_PROP, "http://localhost:9200");
        propertiesProvider.overrideWith(properties);
        super.configure();
    }
}
