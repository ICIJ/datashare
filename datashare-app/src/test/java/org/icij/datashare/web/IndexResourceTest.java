package org.icij.datashare.web;

import net.codestory.http.WebServer;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.HashMapUser;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.HashMap;

import static org.icij.datashare.web.IndexResource.getQueryAsString;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

public class IndexResourceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    private static WebServer mockElastic = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    @Mock Indexer mockIndexer;
    @Override public int port() { return server.port();}

    @Test
    public void test_no_auth_get_forward_request_to_elastic() {
        get("/api/index/search/local-datashare/foo/bar").should().respond(200)
                .contain("I am elastic GET")
                .contain("uri=local-datashare/foo/bar");
    }
    @Test
    public void test_no_auth_get_unauthorized_on_unknown_index() {
        get("/api/index/search/hacker/bar/baz").should().respond(401);
    }
    @Test
    public void test_no_auth_post_forward_request_to_elastic_with_body() {
        String body = "{\"body\": \"es\"}";
        post("/api/index/search/local-datashare/foo/bar", body).should().respond(200)
                        .contain("I am elastic POST")
                        .contain("uri=local-datashare/foo/bar")
                        .contain(body);
    }
    @Test
    public void test_auth_forward_request_with_user_logged_on() {
        server.configure(routes -> routes.add(new IndexResource(new PropertiesProvider(new HashMap<String, String>() {{
            put("elasticsearchAddress", "http://localhost:" + mockElastic.port());
        }}), mockIndexer)).filter(new BasicAuthFilter("/", "icij", HashMapUser.singleUser("cecile"))));

        get("/api/index/search/cecile-datashare/foo/bar?routing=baz").withPreemptiveAuthentication("cecile", "").should().respond(200)
                .contain("uri=cecile-datashare/foo/bar?routing=baz");
        post("/api/index/search/cecile-datashare/foo/bar").withPreemptiveAuthentication("cecile", "").should().respond(200)
                .contain("uri=cecile-datashare/foo/bar");

        get("/api/index/search/hacker/foo/bar?routing=baz").withPreemptiveAuthentication("cecile", "").should().respond(401);
        post("/api/index/search/hacker/foo/bar").withPreemptiveAuthentication("cecile", "").should().respond(401);
    }

    @Test
    public void test_auth_forward_request_for_scroll_requests() {
        server.configure(routes -> routes.add(new IndexResource(new PropertiesProvider(new HashMap<String, String>() {{
            put("elasticsearchAddress", "http://localhost:" + mockElastic.port());
        }}), mockIndexer)).filter(new BasicAuthFilter("/", "icij", HashMapUser.singleUser("cecile"))));

        post("/api/index/search/_search/scroll").withPreemptiveAuthentication("cecile", "").should().respond(200);
    }

    @Test
    public void test_delete_should_return_method_not_allowed() {
        delete("/api/index/search/foo/bar").should().respond(405);
    }

    @Test
    public void test_put_should_return_method_not_allowed() {
        server.configure(routes -> routes.add(new IndexResource(new PropertiesProvider(new HashMap<String, String>() {{
            put("elasticsearchAddress", "http://localhost:" + mockElastic.port());
        }}), mockIndexer)).filter(new BasicAuthFilter("/", "icij", HashMapUser.singleUser("cecile"))));

        put("/api/index/search/cecile-datashare/_search").withPreemptiveAuthentication("cecile", "pass").should().respond(405);
    }

    @Test
    public void test_put_create_local_index_in_local_mode() throws Exception {
        put("/api/index/indexName").should().respond(200);
        verify(mockIndexer).createIndex("indexName");
    }

    @Test
    public void test_put_createIndex_calls_indexer() throws Exception {
        server.configure(routes -> routes.add(new IndexResource(new PropertiesProvider(new HashMap<String, String>() {{
            put("elasticsearchAddress", "http://localhost:" + mockElastic.port());
        }}), mockIndexer)).filter(new BasicAuthFilter("/", "icij", HashMapUser.singleUser("cecile"))));
        put("/api/index/cecile-datashare").withPreemptiveAuthentication("cecile", "pass").should().respond(200);
        verify(mockIndexer).createIndex("cecile-datashare");
    }

    @Before
    public void setUp() {
        initMocks(this);
        server.configure(routes -> routes.add(new IndexResource(new PropertiesProvider(new HashMap<String, String>() {{
            put("elasticsearchAddress", "http://localhost:" + mockElastic.port());
        }}), mockIndexer)).filter(new LocalUserFilter(new PropertiesProvider())));
        mockElastic.configure(routes -> routes
            .get("/:uri:", (context, uri) -> "I am elastic GET uri=" + uri + "?" + getQueryAsString(context.query()))
            .post("/:uri:", (context, uri) -> "I am elastic POST uri=" + uri + " " + new String(context.request().contentAsBytes()))
        );
    }
}

