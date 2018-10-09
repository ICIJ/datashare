package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Response;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.junit.ClassRule;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;

public class ElasticsearchConfigurationTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();

    @Test
    public void test_create_client_creates_mapping() throws Exception {
        ElasticsearchConfiguration.createESClient(new PropertiesProvider());

        Response response = es.client.getLowLevelClient().performRequest("GET", TEST_INDEX);

        assertThat(EntityUtils.toString(response.getEntity())).contains("mapping");
    }

    @Test
    public void test_create_client_creates_settings() throws Exception {
        ElasticsearchConfiguration.createESClient(new PropertiesProvider());

        Response response = es.client.getLowLevelClient().performRequest("GET", TEST_INDEX);

        assertThat(EntityUtils.toString(response.getEntity())).contains("settings");
    }
}
