package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.test.ElasticsearchRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.MAPPING_RESOURCE_NAME;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.SETTINGS_RESOURCE_NAME;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.SETTINGS_RESOURCE_NAME_WINDOWS;

public class ElasticsearchConfigurationTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();

    @Test
    public void test_create_client_creates_mapping() throws Exception {
        ElasticsearchConfiguration.createESClient(new PropertiesProvider());

        RestClient restClient = ((RestClientTransport) es.client._transport()).restClient();
        Response response = restClient.performRequest(new Request("GET", es.getIndexName()));

        assertThat(EntityUtils.toString(response.getEntity())).contains("mapping");
    }

    @Test
    public void test_create_client_creates_settings() throws Exception {
        ElasticsearchConfiguration.createESClient(new PropertiesProvider());

        RestClient restClient = ((RestClientTransport) es.client._transport()).restClient();
        Response response = restClient.performRequest(new Request("GET", es.getIndexName()));

        assertThat(EntityUtils.toString(response.getEntity())).contains("settings");
    }

    @Test
    public void test_create_client_with_user_pass() throws Exception {
        String esUri = EnvUtils.resolveUri("elasticsearch", "http://localhost:9200");
        String esUriWithAuth = esUri.replace("://", "://user:pass@");
        ElasticsearchClient esClient = ElasticsearchConfiguration.createESClient(new PropertiesProvider(new HashMap<>() {{
            put("elasticsearchAddress", esUriWithAuth);
        }}));

        RestClient restClient = ((RestClientTransport) esClient._transport()).restClient();
        Response response = restClient.performRequest(new Request("GET", es.getIndexName()));

        assertThat(EntityUtils.toString(response.getEntity())).contains("settings");
    }

    @Test
    public void test_create_client_with_x_elastic_post() throws  Exception {
        ElasticsearchConfiguration.createESClient(new PropertiesProvider());

        RestClient restClient = ((RestClientTransport) es.client._transport()).restClient();
        Response response = restClient.performRequest(new Request("GET", es.getIndexName()));

        assertThat(response.getHeader("X-Elastic-Product")).isNotNull();
    }

    @Test
    public void test_create_client_sends_plain_json_content_type() throws Exception {
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requestHeaders.set(exchange.getRequestHeaders());
            byte[] body = "{\"took\":1,\"timed_out\":false,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0},\"hits\":{\"hits\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String address = "http://localhost:" + server.getAddress().getPort();
            ElasticsearchClient esClient = ElasticsearchConfiguration.createESClient(new PropertiesProvider(new HashMap<>() {{
                put("elasticsearchAddress", address);
            }}));

            esClient.search(s -> s.index("test"), Object.class);

            assertThat(requestHeaders.get().getFirst("Content-Type")).isEqualTo("application/json");
            assertThat(requestHeaders.get().getFirst("Accept")).isEqualTo("application/json");
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void test_create_index_body_keeps_match_mapping_type_scalar() throws Exception {
        String settingsResource = IS_OS_WINDOWS ? SETTINGS_RESOURCE_NAME_WINDOWS : SETTINGS_RESOURCE_NAME;
        JsonNode body = JsonObjectMapper.getMapper().readTree(
                ElasticsearchConfiguration.createIndexBody(MAPPING_RESOURCE_NAME, settingsResource));

        JsonNode matchMappingType = null;
        for (JsonNode template : body.at("/mappings/dynamic_templates")) {
            if (template.has("metadata_dates")) {
                matchMappingType = template.at("/metadata_dates/match_mapping_type");
            }
        }
        assertThat(matchMappingType).isNotNull();
        assertThat(matchMappingType.isTextual()).isTrue();
        assertThat(matchMappingType.asText()).isEqualTo("date");
        assertThat(body.get("settings").isObject()).isTrue();
        assertThat(body.get("mappings").isObject()).isTrue();
    }
}
