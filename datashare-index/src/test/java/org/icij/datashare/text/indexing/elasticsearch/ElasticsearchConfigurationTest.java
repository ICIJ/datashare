package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestHighLevelClient;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;

public class ElasticsearchConfigurationTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();

    @Test
    public void test_create_client_creates_mapping() throws Exception {
        ElasticsearchConfiguration.createESClient(new PropertiesProvider());

        Response response = es.client.getLowLevelClient().performRequest(new Request("GET", TEST_INDEX));

        assertThat(EntityUtils.toString(response.getEntity())).contains("mapping");
    }

    @Test
    public void test_create_client_creates_settings() throws Exception {
        ElasticsearchConfiguration.createESClient(new PropertiesProvider());

        Response response = es.client.getLowLevelClient().performRequest(new Request("GET", TEST_INDEX));

        assertThat(EntityUtils.toString(response.getEntity())).contains("settings");
    }

    @Test
    public void test_create_client_with_user_pass() throws Exception {
        RestHighLevelClient esClient = ElasticsearchConfiguration.createESClient(new PropertiesProvider(new HashMap<String, String>() {{
            put("elasticsearchAddress", "http://user:pass@elasticsearch:9200");
        }}));

        Response response = esClient.getLowLevelClient().performRequest(new Request("GET", TEST_INDEX));

        assertThat(EntityUtils.toString(response.getEntity())).contains("settings");
    }
}
