package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

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
}
