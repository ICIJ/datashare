package org.icij.datashare.test;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest.Builder;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicHeader;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.StringUtils;
import org.junit.rules.ExternalResource;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.IntStream;

import static com.google.common.io.ByteStreams.toByteArray;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.apache.http.HttpHost.create;

public class ElasticsearchRule extends ExternalResource {
    private static final String TEST_INDEX = "test-datashare";
    private static final String MAPPING_RESOURCE_NAME = "datashare_index_mappings.json";
    private static final String SETTINGS_RESOURCE_NAME = "datashare_index_settings.json";
    public final ElasticsearchClient client;
    private final String[] indexesNames;

    public ElasticsearchRule() {
        this(generateIndexName());
    }

    public ElasticsearchRule(final String... indexesNames) {
        this(indexesNames, create("http://elasticsearch:9200"));
    }

    public ElasticsearchRule(int nbIndices) {
        this(IntStream.range(0, nbIndices).mapToObj(i -> generateIndexName()).toArray(String[]::new), create("http://elasticsearch:9200"));
    }

    private ElasticsearchRule(final String[] indexesName, HttpHost elasticHost) {
        this.indexesNames = indexesName;
        System.setProperty("es.set.netty.runtime.available.processors", "false");
        RestClientBuilder.HttpClientConfigCallback xElasticProductCallback = httpAsyncClientBuilder -> {
            httpAsyncClientBuilder.disableAuthCaching();
            httpAsyncClientBuilder.setDefaultHeaders(
                    singletonList(new BasicHeader("Content-type", "application/json")));
            httpAsyncClientBuilder.addInterceptorLast((HttpResponseInterceptor)
                    (response, context) ->
                            // This header is expected from the client, versions of ES server below 7.14 don't provide it
                            // i.e : https://www.elastic.co/guide/en/elasticsearch/reference/7.17/release-notes-7.14.0.html
                            response.addHeader("X-Elastic-Product", "Elasticsearch"));
            return httpAsyncClientBuilder;
        };
        RestClient rest = RestClient.builder(elasticHost)
                .setHttpClientConfigCallback(xElasticProductCallback)
                .build();
        client = new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper(JsonObjectMapper.getMapper())));
    }

    @Override
    protected void before() throws Throwable {
        for (String index : indexesNames) {
            ExistsRequest existsRequest = ExistsRequest.of(er -> er.index(index));
            if (!client.indices().exists(existsRequest).value()) {
                Builder createReq = new Builder().index(index);
                String settings = new String(toByteArray(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(SETTINGS_RESOURCE_NAME))));
                createReq.settings(IndexSettings.of(is -> is.withJson(new StringReader(settings))));
                String mappings = new String(toByteArray(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(MAPPING_RESOURCE_NAME))));
                createReq.mappings(TypeMapping.of(tm -> tm.withJson(new StringReader(mappings))));
                client.indices().create(createReq.build());
            }
        }
    }

    @Override
    protected void after() {
        try {
            client.indices().delete(DeleteIndexRequest.of(dir -> dir.index(asList(indexesNames))));
            client._transport().close();
        } catch (IOException e) {
            throw new IllegalStateException("cannot close ES client properly", e);
        }
    }

    public void delete(String... indices) throws IOException {
        for (String index : indices) {
            Request request = new Request("DELETE", index);
            request.addParameter("ignore_unavailable", "true");
            RestClient restClient = ((RestClientTransport) client._transport()).restClient();
            restClient.performRequest(request);
        }
    }

    public void removeAll() throws IOException {
        for (String index : indexesNames) {
            Request post = new Request("POST", index + "/_delete_by_query");
            post.addParameter("refresh", "true");
            post.setEntity(new NStringEntity("{\"query\": {\"match_all\": {}}}", ContentType.APPLICATION_JSON));
            RestClient restClient = ((RestClientTransport) client._transport()).restClient();
            Response response = restClient.performRequest(post);
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new RuntimeException("error while executing delete by query status : " + response.getStatusLine().getStatusCode());
            }
        }
    }

    public String getIndexName() {
        return indexesNames[0];
    }

    public String[] getIndexNames() {
        return indexesNames;
    }

    public static String generateIndexName() {
        return TEST_INDEX + "-" + StringUtils.generateString(8);
    }
}
