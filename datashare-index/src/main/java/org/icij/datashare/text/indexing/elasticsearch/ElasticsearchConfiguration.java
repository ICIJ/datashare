package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static java.net.InetAddress.getByName;

class ElasticsearchConfiguration {
    static final int INDEX_MAX_RESULT_WINDOW = 100000;
    static Logger LOGGER = LoggerFactory.getLogger(ElasticsearchConfiguration.class);
    public static final String VERSION = "6.1.0";

    static protected final int DEFAULT_SEARCH_FROM = 0;
    static protected final int DEFAULT_SEARCH_SIZE = 100;
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

    private static final String DEFAULT_INDEX_TYPE = "doc";
    private static final String DEFAULT_INDEX_JOIN_FIELD = "join";
    static final String DEFAULT_PARENT_DOC_FIELD = "parentDocument";

    private static final String DEFAULT_DOC_TYPE_FIELD = "type";
    private static final String DEFAULT_INDEX_NAME = "datashare-local";

    final String indexType;
    final String indexName;
    final String indexJoinField;
    final String docTypeField;
    WriteRequest.RefreshPolicy refreshPolicy = WriteRequest.RefreshPolicy.NONE;

    final int shards = 1;
    final int replicas = 1;

    ElasticsearchConfiguration(PropertiesProvider propertiesProvider) {
        indexType = propertiesProvider.get(INDEX_TYPE_PROP).orElse(DEFAULT_INDEX_TYPE);
        indexJoinField = propertiesProvider.get(INDEX_JOIN_FIELD_NAME_PROP).orElse(DEFAULT_INDEX_JOIN_FIELD);
        docTypeField = propertiesProvider.get(INDEX_TYPE_FIELD_NAME_PROP).orElse(DEFAULT_DOC_TYPE_FIELD);
        indexName = propertiesProvider.get(INDEX_NAME_PROP).orElse(DEFAULT_INDEX_NAME);
    }

    static Client createESClient(final PropertiesProvider propertiesProvider) throws UnknownHostException {
        System.setProperty("es.set.netty.runtime.available.processors", "false");

        String indexAddress = propertiesProvider.get(INDEX_ADDRESS_PROP).orElse(DEFAULT_ADDRESS);
        InetAddress esAddress = getByName(indexAddress.split(":")[0]);
        int esPort = Integer.parseInt(indexAddress.split(":")[1]);
        String clusterName = propertiesProvider.get(CLUSTER_PROP).orElse(ES_CLUSTER_NAME);

        Settings settings = Settings.builder().put("cluster.name", clusterName).build();
        LOGGER.info("Opening connection to " + "[" + indexAddress + "]" + " node(s)");
        LOGGER.info("Settings :");
        LOGGER.info(settings.toDelimitedString('\n'));
        return new PreBuiltTransportClient(settings).addTransportAddress(
                new TransportAddress(esAddress, esPort));
    }

    ElasticsearchConfiguration withRefresh(WriteRequest.RefreshPolicy refreshPolicy) {
        this.refreshPolicy = refreshPolicy;
        return this;
    }

    @Override
    public String toString() {
        return "cfg{" +
                "indexName='" + indexName + '\'' +
                ", indexType='" + indexType + '\'' +
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
}
