package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;

public class SearchResourceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    private static WebServer mockElastic = new WebServer() {
        @Override
        protected Env createEnv() { return Env.prod();}}.startOnRandomPort();
    @Override public int port() { return server.port();}

    @Test
    public void test_no_auth_get_forward_request_to_elastic() {
        get("/search/foo/bar").should().respond(200)
                .contain("I am elastic GET")
                .contain("uri=foo/bar");
    }
    @Test
    public void test_no_auth_post_forward_request_to_elastic_with_body() {
        String body = "{\"body\": \"es\"}";
        post("/search/foo/bar", body).should().respond(200)
                        .contain("I am elastic POST")
                        .contain("uri=foo/bar")
                        .contain(body);
    }
    @Test
    public void test_delete_should_return_method_not_allowed() {
        delete("/search/foo/bar").should().respond(405);
    }

    @BeforeClass
    public static void setUp() {
        server.configure(routes -> routes.add(new SearchResource(new PropertiesProvider(new HashMap<String, String>() {{
            put("elasticsearchUrl", "http://localhost:" + mockElastic.port());
        }}))));
        mockElastic.configure(routes -> routes
            .get("/:uri", (context, uri) -> "I am elastic GET uri=" + uri)
            .post("/:uri", (context, uri) -> "I am elastic POST uri=" + uri + " " + new String(context.request().contentAsBytes()))
    );}
}

