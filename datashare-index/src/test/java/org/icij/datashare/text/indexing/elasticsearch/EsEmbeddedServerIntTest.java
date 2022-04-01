package org.icij.datashare.text.indexing.elasticsearch;

import org.elasticsearch.client.indices.GetIndexRequest;
import org.icij.datashare.test.ElasticsearchRule;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.apache.http.HttpHost.create;
import static org.elasticsearch.client.RequestOptions.DEFAULT;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;

public class EsEmbeddedServerIntTest {
    private static EsEmbeddedServer server;
    @ClassRule static public TemporaryFolder esDir = new TemporaryFolder();
    @Rule public ElasticsearchRule es = new ElasticsearchRule(create("http://localhost:9200"));

    @Test
    public void test_embedded_server_has_a_test_index() throws Exception {
        assertThat(es.client.indices().exists(new GetIndexRequest(TEST_INDEX), DEFAULT)).isTrue();
    }

    @BeforeClass
    public static void setUp() {
        server = new EsEmbeddedServer("datashare", esDir.getRoot().getPath(), esDir.getRoot().getPath(), "9200");
        server.start();
    }

    @AfterClass
    public static void tearDown() throws IOException { server.stop();}
}
