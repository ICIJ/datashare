package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
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

        GetMappingsResponse mappings = es.client.admin().indices().getMappings(new GetMappingsRequest()).actionGet();

        assertThat(mappings.getMappings().get(TEST_INDEX)).isNotEmpty();
    }
}
