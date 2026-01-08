package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.test.ElasticsearchRule;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.apache.http.HttpHost.create;
import static org.fest.assertions.Assertions.assertThat;

public class EsEmbeddedServerIntTest {
    private static EsEmbeddedServer server;
    @ClassRule static public TemporaryFolder esDir = new TemporaryFolder();
    @Rule public ElasticsearchRule es = new ElasticsearchRule();

    @Test(timeout = 10_000)
    public void test_embedded_server_has_a_test_index() throws Exception {
         assertThat(es.client.indices().exists(e -> e.index(es.getIndexName())).value()).isTrue();
    }

    @BeforeClass
    public static void setUp() {
        server = new EsEmbeddedServer("datashare", esDir.getRoot().getPath(), esDir.getRoot().getPath(), "9222", "9333");
        server.start();
    }

    @AfterClass
    public static void tearDown() throws IOException { server.close();}
}
