package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.settings.Settings;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.google.common.io.ByteStreams.toByteArray;
import static org.apache.http.HttpHost.create;
import static org.elasticsearch.common.xcontent.XContentType.JSON;

public class ElasticsearchConfiguration {
    static final String MAPPING_RESOURCE_NAME = "datashare_index_mappings.json";
    static final String SETTINGS_RESOURCE_NAME = "datashare_index_settings.json";
    static final int INDEX_MAX_RESULT_WINDOW = 100000;
    static Logger LOGGER = LoggerFactory.getLogger(ElasticsearchConfiguration.class);
    public static final String VERSION = "6.1.0";

    static protected final int DEFAULT_SEARCH_FROM = 0;
    static protected final int DEFAULT_SEARCH_SIZE = 10000;
    static protected final int DEFAULT_TIMEOUT_INSEC = 10;

    private static final String INDEX_ADDRESS_PROP = "elasticsearchAddress";
    private static final String INDEX_TYPE_PROP = "indexType";
    private static final String INDEX_NAME_PROP = "indexName";
    private static final String INDEX_JOIN_FIELD_NAME_PROP = "indexJoinFieldName";
    private static final String INDEX_TYPE_FIELD_NAME_PROP = "indexTypeFieldName";
    private static final String CLUSTER_PROP = "clusterName";

    private static final String DEFAULT_ADDRESS = "http://localhost:9200";
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

    public static RestHighLevelClient createESClient(final PropertiesProvider propertiesProvider) {
        System.setProperty("es.set.netty.runtime.available.processors", "false");

        String indexAddress = propertiesProvider.get(INDEX_ADDRESS_PROP).orElse(DEFAULT_ADDRESS);

        RestHighLevelClient client = new RestHighLevelClient(RestClient.builder(create(indexAddress)).setRequestConfigCallback(
                requestConfigBuilder -> requestConfigBuilder
                    .setConnectTimeout(5000)
                    .setSocketTimeout(60000)));
        String clusterName = propertiesProvider.get(CLUSTER_PROP).orElse(ES_CLUSTER_NAME);
        return client;
    }

    public static boolean createIndex(RestHighLevelClient client, String indexName, PropertiesProvider propertiesProvider) {
        return createIndex(client, indexName, propertiesProvider.get(INDEX_TYPE_PROP).orElse(DEFAULT_INDEX_TYPE));
    }

    public static boolean createIndex(RestHighLevelClient client, String indexName, String indexType) {
        GetIndexRequest request = new GetIndexRequest();
        request.indices(indexName);
        try {
            if (!client.indices().exists(request)) {
                LOGGER.info("index {} does not exist, creating one", indexName);
                CreateIndexRequest createReq = new CreateIndexRequest(indexName);
                createReq.settings(getResourceContent(SETTINGS_RESOURCE_NAME), JSON);
                createReq.mapping(indexType, getResourceContent(MAPPING_RESOURCE_NAME), JSON);
                client.indices().create(createReq);
                return true;
            }
        } catch (IOException e) {
            throw new ConfigurationException(e);
        }
        return false;
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

    private static String getResourceContent(String resourceName) {
        byte[] resourceBytes;
        try {
            resourceBytes = toByteArray(ElasticsearchConfiguration.class.getClassLoader().getResourceAsStream(resourceName));
        } catch (IOException e) {
            throw new ConfigurationException(e);
        }
        return new String(resourceBytes);
    }

    static class ConfigurationException extends RuntimeException {
        ConfigurationException(Exception source) {
            super(source);
        }
    }
}
