package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;

import static com.google.common.io.ByteStreams.toByteArray;
import static java.net.InetAddress.getByName;
import static org.elasticsearch.common.xcontent.XContentType.JSON;

public class ElasticsearchConfiguration {
    static final String MAPPING_RESOURCE_NAME = "datashare_index_mappings.json";
    static final int INDEX_MAX_RESULT_WINDOW = 100000;
    static Logger LOGGER = LoggerFactory.getLogger(ElasticsearchConfiguration.class);
    public static final String VERSION = "6.1.0";

    static protected final int DEFAULT_SEARCH_FROM = 0;
    static protected final int DEFAULT_SEARCH_SIZE = 10000;
    static protected final int DEFAULT_TIMEOUT_INSEC = 10;

    private static final String INDEX_ADDRESS_PROP = "indexAddress";
    private static final String INDEX_TYPE_PROP = "indexType";
    private static final String INDEX_NAME_PROP = "indexName";
    private static final String INDEX_JOIN_FIELD_NAME_PROP = "indexJoinFieldName";
    private static final String INDEX_TYPE_FIELD_NAME_PROP = "indexTypeFieldName";
    private static final String CLUSTER_PROP = "clusterName";

    private static final String DEFAULT_ADDRESS = "localhost:9300";
    private static final String ES_CLUSTER_NAME = "datashare";
    static final String  ES_DOCUMENT_TYPE = "Document";
    static final String  ES_CONTENT_FIELD = "content";

    static final String DEFAULT_INDEX_TYPE = "doc";
    private static final String DEFAULT_INDEX_JOIN_FIELD = "join";
    static final String DEFAULT_PARENT_DOC_FIELD = "parentDocument";

    private static final String DEFAULT_DOC_TYPE_FIELD = "type";

    final String indexType;
    final String indexJoinField;
    final String docTypeField;
    WriteRequest.RefreshPolicy refreshPolicy = WriteRequest.RefreshPolicy.NONE;

    final int shards = 1;
    final int replicas = 1;

    ElasticsearchConfiguration(PropertiesProvider propertiesProvider) {
        indexType = propertiesProvider.get(INDEX_TYPE_PROP).orElse(DEFAULT_INDEX_TYPE);
        indexJoinField = propertiesProvider.get(INDEX_JOIN_FIELD_NAME_PROP).orElse(DEFAULT_INDEX_JOIN_FIELD);
        docTypeField = propertiesProvider.get(INDEX_TYPE_FIELD_NAME_PROP).orElse(DEFAULT_DOC_TYPE_FIELD);
    }

    public static Client createESClient(final PropertiesProvider propertiesProvider) {
        System.setProperty("es.set.netty.runtime.available.processors", "false");

        String indexAddress = propertiesProvider.get(INDEX_ADDRESS_PROP).orElse(DEFAULT_ADDRESS);
        int esPort = Integer.parseInt(indexAddress.split(":")[1]);
        String clusterName = propertiesProvider.get(CLUSTER_PROP).orElse(ES_CLUSTER_NAME);

        Settings settings = Settings.builder().put("cluster.name", clusterName).build();
        LOGGER.info("Opening connection to " + "[" + indexAddress + "]" + " node(s)");
        LOGGER.info("Settings :");
        LOGGER.info(settings.toDelimitedString('\n'));

        try {
            InetAddress esAddress = getByName(indexAddress.split(":")[0]);
            return new PreBuiltTransportClient(settings).addTransportAddress(
                    new TransportAddress(esAddress, esPort));
        } catch (IOException ioex) {
            throw new ConfigurationException(ioex);
        }
    }

    public static void createIndex(Client client, String indexName) throws IOException {
        if (!client.admin().indices().prepareExists(indexName).execute().actionGet().isExists()) {
            LOGGER.info("index {} does not exist, creating one", indexName);
            client.admin().indices().create(new CreateIndexRequest(indexName)).actionGet();
        }

        GetMappingsResponse mappings = client.admin().indices().getMappings(new GetMappingsRequest()).actionGet();
        ImmutableOpenMap<String, MappingMetaData> mapping = mappings.getMappings().get(indexName);
        if (mapping == null || mapping.isEmpty()) {
            LOGGER.info("creating mapping for index {}", indexName);
            byte[] mappingAsBytes = toByteArray(ElasticsearchConfiguration.class.getClassLoader().getResourceAsStream(MAPPING_RESOURCE_NAME));
            client.admin().indices().preparePutMapping(indexName).setType("doc").setSource(new String(mappingAsBytes), JSON).
                    execute().actionGet();
        }
    }

    ElasticsearchConfiguration withRefresh(WriteRequest.RefreshPolicy refreshPolicy) {
        this.refreshPolicy = refreshPolicy;
        return this;
    }

    @Override
    public String toString() {
        return "cfg{" +
                "indexType='" + indexType + '\'' +
                ", indexJoinField='" + indexJoinField + '\'' +
                ", docTypeField='" + docTypeField + '\'' +
                ", shards=" + shards +
                ", replicas=" + replicas +
                '}';
    }

    Settings getIndexSettings() {
        return Settings.builder()
                .put("index.number_of_shards",   shards)
                .put("index.number_of_replicas", replicas)
                .put("index.max_result_window",  INDEX_MAX_RESULT_WINDOW)
                .build();
    }

    static class ConfigurationException extends RuntimeException {
        ConfigurationException(Exception source) {
            super(source);
        }
    }
}
