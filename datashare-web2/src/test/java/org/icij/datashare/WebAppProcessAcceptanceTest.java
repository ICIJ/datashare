package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import net.codestory.rest.RestAssert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URLEncoder;

public class WebAppProcessAcceptanceTest implements FluentRestTest {
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();

    @Override
    public int port() {
        return server.port();
    }

    @BeforeClass
    public static void setUpClass() {
        server.configure(WebApp.getConfiguration());
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

    @Test
    public void testIndexDirectory() throws Exception {
        RestAssert response = post("/process/index/file/" + getClass().getResource("/docs/").getPath().replace("/","%7C"));

        response.should().haveType("application/json");
        response.should().contain("{\"result\":\"OK\"}");
    }
}
