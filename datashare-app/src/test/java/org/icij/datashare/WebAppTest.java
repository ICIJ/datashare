package org.icij.datashare;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.WebApp.buildNlpWorkersProcess;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.utils.ProcessHandler.dumpPid;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class WebAppTest {

    private static final Path extDir = Path.of(Objects.requireNonNull(
        WebAppTest.class.getClassLoader().getResource("extensions")).getPath());

    public static final String extRepoContent = "{\"deliverableList\": ["
        + "{\"id\":\"datashare-nlp-spacy\", \"url\": \""
        + "https://github.com/ICIJ/datashare-spacy-worker/releases/download/0.1.3/datashare-spacy-worker-0.1.3"
        + "\"}"
        + "]}";

    @Test
    public void test_nlp_workers_process() throws IOException, InterruptedException {
        // Given
        String extensionId = "datashare-nlp-spacy";
        ExtensionService extensionService = new ExtensionService(extDir, new ByteArrayInputStream(extRepoContent.getBytes()));
        ExecutableExtensionHelper extensionHelper = new ExecutableExtensionHelper(extensionService, extensionId);
        // When
        Process p = buildNlpWorkersProcess(extensionHelper, 6).start();
        // Then
        int timeout = 2;
        TimeUnit unit = TimeUnit.SECONDS;
        if (!p.waitFor(timeout, unit)) {
            throw new AssertionError("failed to get process output in less than " + timeout + unit.name().toLowerCase());
        }
        Map<String, Object> output = (Map<String, Object>) MAPPER.readValue(p.getInputStream().readAllBytes(), Map.class);
        assertThat(output.get("n_workers")).isEqualTo(6);
        assertThat((String) output.get("config_file")).contains("datashare-spacy-worker-config-");
    }

    @Test(timeout = 20000)
    public void test_nlp_workers_process_should_throw_when_worker_pool_is_running() throws IOException {
        // Given
        String extensionId = "datashare-nlp-spacy";
        ExtensionService extensionService = new ExtensionService(extDir, new ByteArrayInputStream(extRepoContent.getBytes()));
        ExecutableExtensionHelper extensionHelper = new ExecutableExtensionHelper(extensionService, extensionId);
        Process p = null;
        try {
            p = new ProcessBuilder("sleep", "100000").start();
            Path pidPath = Files.createTempFile("datashare-spacy-worker-1.9.0", ".pid");
            dumpPid(pidPath.toFile(), p.pid());
            // When/Then
            assertThat(assertThrows(RuntimeException.class, () -> buildNlpWorkersProcess(extensionHelper, 1).start()).getMessage())
                .matches("found phantom worker running in process.*");
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }
}
