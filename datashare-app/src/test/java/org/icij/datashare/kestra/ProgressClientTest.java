package org.icij.datashare.kestra;

import io.kestra.sdk.KestraClient;
import java.io.IOException;
import org.icij.datashare.asynctasks.Progress;
import org.junit.Test;

public class ProgressClientTest {
    KestraClient client = KestraClient.builder()
            .url("http://localhost:8080")
            .basicAuth("c@icij.com", "Testtest0")
            .build();

    @Test
    public void test_should_get_aggregated_progress() throws IOException, InterruptedException {
        ProgressClient progressClient = new ProgressClient(client);

        String executionId = "Xf0UAWwUU6Z7umcYHzyh7";
        String tenant = "main";
        String namespace = "org.icij.datashare";
        Double currentProgress = null;
        while (currentProgress == null || currentProgress < 100) {
            Progress progress = progressClient.getProgress(executionId, tenant, namespace).block();
            currentProgress = progress.asDouble();
            Thread.sleep(500);
            System.out.println("progress " + currentProgress * 100 + " / 100");
        }
    }
}
