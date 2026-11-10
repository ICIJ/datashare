package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class BindableWebServerTest {
    private static final long MAX_BODY_BYTES = 1024;
    private final HttpClient client = HttpClient.newHttpClient();
    private WebServer server;

    // Expose the protected createEnv() so the test can assert the server forces production mode,
    // which turns off the live-reload server, the source routes and stack-trace error pages.
    private static class ExposedServer extends BindableWebServer {
        ExposedServer() {
            super("localhost", MAX_BODY_BYTES);
        }

        Env env() {
            return createEnv();
        }
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void test_server_forces_production_mode() {
        assertThat(new ExposedServer().env().prodMode()).isTrue();
    }

    @Test
    public void test_server_disables_live_reload() {
        assertThat(new ExposedServer().env().liveReloadServer()).isFalse();
    }

    @Test
    public void test_answers_a_body_within_the_limit() throws Exception {
        startServer(MAX_BODY_BYTES);
        BodyPublisher body = BodyPublishers.ofByteArray(new byte[1024]);
        assertThat(post(body).statusCode()).isEqualTo(200);
    }

    @Test
    public void test_drops_a_declared_body_above_the_limit() {
        startServer(MAX_BODY_BYTES);
        BodyPublisher body = BodyPublishers.ofByteArray(new byte[1025]);
        assertThrows(IOException.class, () -> post(body));
    }

    @Test
    public void test_drops_a_chunked_body_above_the_limit() {
        startServer(MAX_BODY_BYTES);
        BodyPublisher body = BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[64 * 1024]));
        assertThrows(IOException.class, () -> post(body));
    }

    @Test
    public void test_answers_any_body_when_the_limit_is_zero() throws Exception {
        startServer(0);
        BodyPublisher body = BodyPublishers.ofByteArray(new byte[64 * 1024]);
        assertThat(post(body).statusCode()).isEqualTo(200);
    }

    private void startServer(long maxBodyBytes) {
        server = new BindableWebServer("localhost", maxBodyBytes);
        server.configure(routes -> routes.post("/echo", context -> "ok"));
        server.startOnRandomPort();
    }

    private HttpResponse<String> post(BodyPublisher body) throws IOException, InterruptedException {
        URI uri = URI.create("http://localhost:" + server.port() + "/echo");
        HttpRequest request = HttpRequest.newBuilder(uri).POST(body).build();
        return client.send(request, BodyHandlers.ofString());
    }
}
