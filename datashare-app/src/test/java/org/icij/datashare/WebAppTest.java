package org.icij.datashare;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.WebApp.buildNlpWorkersProcess;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.utils.ProcessHandler.dumpPid;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class WebAppTest {
    private static final String extDir = Objects.requireNonNull(
        WebAppTest.class.getClassLoader().getResource("extensions")).getPath();
    private static final Map<String, Object> properties = Map.of(
        "mode", "EMBEDDED", "nlpParallelism", "6", "extensionsDir", extDir
    );

    @Test
    public void test_nlp_workers_process() throws IOException, InterruptedException {
        // Given
        String extensionPattern = "^datashare-spacy-worker(?:-[\\d\\.]+)?$";
        ExtensionService extensionService = new ExtensionService(Path.of(extDir));
        PropertiesProvider propertiesProvider = new PropertiesProvider(properties);
        ExecutableExtensionHelper extensionHelper = new ExecutableExtensionHelper(
            propertiesProvider, extensionService, extensionPattern);
        // When
        Process p = buildNlpWorkersProcess(extensionHelper, 6, false);
        // Then
        int timeout = 2;
        TimeUnit unit = TimeUnit.SECONDS;
        if (!p.waitFor(timeout, unit)) {
            throw new AssertionError(
                "failed to get process output in less than " + timeout + unit.name().toLowerCase());
        }
        HashMap<String, Object> output = (HashMap<String, Object>) MAPPER.readValue(
            p.getInputStream().readAllBytes(), Map.class);
        assertThat(output.get("n_workers")).isEqualTo(6);
        assertThat((String) output.get("config_file")).contains("datashare-spacy-worker-config-");
    }

    @Test(timeout = 20000)
    public void test_nlp_workers_process_should_throw_when_worker_pool_is_running() throws IOException {
        // Given
        String extensionPattern = "^datashare-spacy-worker(?:-[\\d\\.]+)?$";
        ExtensionService extensionService = new ExtensionService(Path.of(extDir));
        PropertiesProvider propertiesProvider = new PropertiesProvider(properties);
        ExecutableExtensionHelper extensionHelper =
            new ExecutableExtensionHelper(propertiesProvider, extensionService, extensionPattern);
        Process p = null;
        try {
            p = new ProcessBuilder("sleep", "100000").start();
            Path pidPath = Files.createTempFile("datashare-spacy-worker-1.9.0", ".pid");
            dumpPid(pidPath.toFile(), p.pid());
            // When/Then
            assertThat(
                assertThrows(RuntimeException.class, () -> buildNlpWorkersProcess(extensionHelper, 1, false)).getMessage())
                .matches("found phantom worker running in process.*");
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }
}
