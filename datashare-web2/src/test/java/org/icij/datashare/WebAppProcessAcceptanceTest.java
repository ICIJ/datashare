package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.RestAssert;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;

public class WebAppProcessAcceptanceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    private static Client esClient;

    @Override
    public int port() {
        return server.port();
    }

    @BeforeClass
    public static void setUpClass() throws UnknownHostException {
        server.configure(WebApp.getConfiguration());
        Settings settings = Settings.builder().put("cluster.name", "docker-cluster").build();
        esClient = new PreBuiltTransportClient(settings).addTransportAddress(
                new TransportAddress(InetAddress.getByName("elasticsearch"), 9300));
        esClient.admin().indices().create(new CreateIndexRequest("test"));
    }

    @AfterClass
    public static void tearDown() throws Exception {
        esClient.admin().indices().delete(new DeleteIndexRequest("test"));
        esClient.close();
    }

    @Test
    public void testRoot() throws Exception {
        get("/").should().contain("Datashare REST API");
    }

    @Test
    public void testIndexUnknownDirectory() throws Exception {
        RestAssert response = post("/process/index/file/" + URLEncoder.encode("foo|bar", "utf-8"));

        response.should().haveType("application/json");
        response.should().contain("{\"result\":\"Error\"}");
    }

    @Test
    public void testIndexFile() throws Exception {
        RestAssert response = post("/process/index/file/" + getClass().getResource("/docs/doc.txt").getPath().replace("/","%7C"));

        response.should().haveType("application/json");
        response.should().contain("{\"result\":\"Error\"}");
    }

//    @Test
//    public void testIndexDirectory() throws Exception {
//        RestAssert response = post("/process/index/file/" + getClass().getResource("/docs/").getPath().replace("/","%7C"));
//
//        response.should().haveType("application/json");
//        response.should().contain("{\"result\":\"OK\"}");
//        GetResponse documentFields = esClient.get(new GetRequest("test", "doc", "doc.txt")).get();
//        assertTrue(documentFields.isExists());
//    }
}
