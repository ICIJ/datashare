package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.opensearch.common.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;

import static com.google.common.io.ByteStreams.toByteArray;
import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.apache.http.HttpHost.create;

public class ElasticsearchConfiguration {
    static final String MAPPING_RESOURCE_NAME = "datashare_index_mappings.json";
    static final String SETTINGS_RESOURCE_NAME = "datashare_index_settings.json";
    static final String SETTINGS_RESOURCE_NAME_WINDOWS = "datashare_index_settings_windows.json";
    static final int INDEX_MAX_RESULT_WINDOW = 100000;
    static Logger LOGGER = LoggerFactory.getLogger(ElasticsearchConfiguration.class);

    static protected final int DEFAULT_SEARCH_FROM = 0;
    static protected final int DEFAULT_SEARCH_SIZE = 10000;
    static protected final int DEFAULT_TIMEOUT_INSEC = 10;

    public static final String INDEX_ADDRESS_PROP = "elasticsearchAddress";
    public static final String INDEX_NAME_PROP = "indexName";
    public static final String INDEX_JOIN_FIELD_NAME_PROP = "indexJoinFieldName";
    public static final String INDEX_TYPE_FIELD_NAME_PROP = "indexTypeFieldName";
    public static final String CLUSTER_PROP = "clusterName";

    public static final String DEFAULT_ADDRESS = "http://localhost:9200";
    public static final String ES_CLUSTER_NAME = "datashare";
    static final String  ES_DOCUMENT_TYPE = "Document";
    static final String  ES_DUPLICATE_TYPE = "Duplicate";
    static final String  ES_CONTENT_FIELD = "content";

    private static final String DEFAULT_INDEX_JOIN_FIELD = "join";
    static final String DEFAULT_PARENT_DOC_FIELD = "parentDocument";

    private static final String DEFAULT_DOC_TYPE_FIELD = "type";

    final String indexJoinField;
    final String docTypeField;
    Refresh refreshPolicy = Refresh.False;

    final int shards = 1;
    final int replicas = 1;

    ElasticsearchConfiguration(PropertiesProvider propertiesProvider) {
        indexJoinField = propertiesProvider.get(INDEX_JOIN_FIELD_NAME_PROP).orElse(DEFAULT_INDEX_JOIN_FIELD);
        docTypeField = propertiesProvider.get(INDEX_TYPE_FIELD_NAME_PROP).orElse(DEFAULT_DOC_TYPE_FIELD);
    }

    public static ElasticsearchClient createESClient(final PropertiesProvider propertiesProvider) {
        System.setProperty("es.set.netty.runtime.available.processors", "false");
        try {
            URL indexUrl = new URL(propertiesProvider.get(INDEX_ADDRESS_PROP).orElse(DEFAULT_ADDRESS));
            HttpHost httpHost = create(format("%s://%s:%d", indexUrl.getProtocol(), indexUrl.getHost(), indexUrl.getPort()));

            RestClientBuilder.HttpClientConfigCallback clientConfigCallback = httpAsyncClientBuilder -> {
                httpAsyncClientBuilder.disableAuthCaching();
                if (propertiesProvider.get("mode").orElse("LOCAL").equals("EMBEDDED")) {
                    httpAsyncClientBuilder.setDefaultHeaders(
                            singletonList(new BasicHeader("Content-type", "application/json")));
                }
                httpAsyncClientBuilder.addInterceptorLast((HttpResponseInterceptor)
                        (response, context) ->
                                // This header is expected from the client, versions of ES server below 7.14 don't provide it
                                // i.e : https://www.elastic.co/guide/en/elasticsearch/reference/7.17/release-notes-7.14.0.html
                                response.addHeader("X-Elastic-Product", "Elasticsearch"));
                if (indexUrl.getUserInfo() != null) {
                    String[] userInfo = indexUrl.getUserInfo().split(":");
                    LOGGER.info("using credentials from url (user={})", userInfo[0]);
                    final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(userInfo[0], userInfo[1]));

                    httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                }
                return httpAsyncClientBuilder;
            };

            RestClientTransport transport = new RestClientTransport(RestClient.builder(httpHost)
                    .setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                            .setConnectTimeout(5000)
                            .setSocketTimeout(60000))
                    .setHttpClientConfigCallback(clientConfigCallback).build(), new JacksonJsonpMapper(JsonObjectMapper.getMapper()));
            ElasticsearchClient client = new ElasticsearchClient(transport);
            String clusterName = propertiesProvider.get(CLUSTER_PROP).orElse(ES_CLUSTER_NAME);
            return client;
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean createIndex(ElasticsearchClient client, String indexName) {
        ExistsRequest existsRequest = ExistsRequest.of(er -> er.index(indexName));
        try {
            if (!client.indices().exists(existsRequest).value()) {
                LOGGER.info("index {} does not exist, creating one", indexName);
                CreateIndexRequest.Builder createReq = new CreateIndexRequest.Builder().index(indexName);
                createReq.settings(IndexSettings.of(is -> is.withJson(ElasticsearchConfiguration.getSettings())));
                createReq.mappings(TypeMapping.of(tm -> tm.withJson(ElasticsearchConfiguration.getMapping())));
                client.indices().create(createReq.build());
                return true;
            }
        } catch (IOException e) {
            throw new ConfigurationException(e);
        }
        return false;
    }

    public static StringReader getSettings() {
        if (IS_OS_WINDOWS) {
            return new StringReader(getResourceContent(SETTINGS_RESOURCE_NAME_WINDOWS));
        }
        return new StringReader(getResourceContent(SETTINGS_RESOURCE_NAME));
    }

    public static StringReader getMapping() {
        return new StringReader(getResourceContent(MAPPING_RESOURCE_NAME));
    }

    ElasticsearchConfiguration withRefresh(Refresh refreshPolicy) {
        this.refreshPolicy = refreshPolicy;
        return this;
    }

    @Override
    public String toString() {
        return "cfg{" +
                "indexJoinField='" + indexJoinField + '\'' +
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
