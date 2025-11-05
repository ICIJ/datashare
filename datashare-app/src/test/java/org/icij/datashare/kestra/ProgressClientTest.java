package org.icij.datashare.kestra;

import io.kestra.sdk.internal.ApiClient;
import java.io.IOException;
import org.icij.datashare.asynctasks.Progress;
import org.junit.Test;

public class ProgressClientTest {
    private static final ApiClient client = new ApiClient()
        .setBasePath("http://localhost:8080");

    @Test
    public void test_should_get_aggregated_progress() throws IOException, InterruptedException {
        ProgressClient progressClient = new ProgressClient(client);

        String executionId = "executionId";
        String tenant = "tenant";
        String namespace = "namespace";
        Float currentProgress = null;
        while (currentProgress == null || currentProgress < 100) {
            Progress progress = progressClient.getProgress(executionId, tenant, namespace);
            currentProgress = progress.asFloat();
            Thread.sleep(500);
            System.out.println("progress " + currentProgress * 100 + " / 100");
        }
    }
}
