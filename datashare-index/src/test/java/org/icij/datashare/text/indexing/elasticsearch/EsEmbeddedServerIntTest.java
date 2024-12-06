package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.test.ElasticsearchRule;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.apache.http.HttpHost.create;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;

public class EsEmbeddedServerIntTest {
    private static EsEmbeddedServer server;
    @ClassRule static public TemporaryFolder esDir = new TemporaryFolder();
    @Rule public ElasticsearchRule es = new ElasticsearchRule(create("http://localhost:9222"));

    @Test(timeout = 10_000)
    public void test_embedded_server_has_a_test_index() throws Exception {
         assertThat(es.client.indices().exists(e -> e.index(TEST_INDEX)).value()).isTrue();
    }

    @BeforeClass
    public static void setUp() {
        server = new EsEmbeddedServer("datashare", esDir.getRoot().getPath(), esDir.getRoot().getPath(), "9222", "9333");
        server.start();
    }

    @AfterClass
    public static void tearDown() throws IOException { server.close();}
}
