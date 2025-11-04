package org.icij.datashare.mode;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.WebApp.waitForServerToBeUp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.Test;

public class EmbeddedModeTest {
    @Test
    public void test_should_run_kestra_webserver() throws IOException, InterruptedException {
        HttpResponse<String> response;

        Map<String, Object> config = Map.of("mode", "EMBEDDED",
            "elasticsearchDataPath", "/tmp/datashare-elasticsearch" // this is avoid the macos fileexception (/home/datashare: Operation not supported)
            );
            try (CommonMode ignored = CommonMode.create(config);
             HttpClient client = HttpClient.newHttpClient()) {
            waitForServerToBeUp(8081);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/health"))
                .GET()
                .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }
}
