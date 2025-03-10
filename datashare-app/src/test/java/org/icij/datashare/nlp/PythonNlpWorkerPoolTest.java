package org.icij.datashare.nlp;

import static org.fest.assertions.Assertions.assertThat;
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
import org.icij.datashare.ExtensionService;
import org.icij.datashare.PropertiesProvider;
import org.junit.After;
import org.junit.Test;

public class PythonNlpWorkerPoolTest {
    private PythonNlpWorkerPool processPool;

    private static final Path extDir = Path.of(Objects.requireNonNull(
        PythonNlpWorkerPoolTest.class.getClassLoader().getResource("extensions")).getPath());

    public static final String extRepoContent = "{\"deliverableList\": ["
        + "{\"id\":\"datashare-extension-nlp-spacy\", \"url\": \""
        + "https://github.com/ICIJ/datashare-extension-nlp-spacy/releases/download/0.1.3/datashare-extension-nlp-spacy-0.1.3"
        + "\"}"
        + "]}";

    private static final ExtensionService extensionService = new ExtensionService(extDir, new ByteArrayInputStream(extRepoContent.getBytes()));

    @After
    public void tearDown() {
        if (processPool != null) {
            try {
                processPool.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void test_python_nlp_worker_pool() throws IOException, InterruptedException {
        // Given
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("nlpParallelism", "6"));
        processPool = new PythonNlpWorkerPool(extensionService, propertiesProvider);
        // When
        Process p = processPool.buildProcess().start();
        // Then
        int timeout = 2;
        TimeUnit unit = TimeUnit.SECONDS;
        if (!p.waitFor(timeout, unit)) {
            throw new AssertionError("failed to get process output in less than " + timeout + unit.name().toLowerCase());
        }
        Map<String, Object> output = (Map<String, Object>) MAPPER.readValue(p.getInputStream().readAllBytes(), Map.class);
        assertThat(output.get("n_workers")).isEqualTo(6);
        assertThat((String) output.get("config_file")).contains("datashare-extension-nlp-spacy-config-");
    }

    @Test(timeout = 20000)
    public void test_nlp_workers_process_should_throw_when_worker_pool_is_running() throws IOException {
        // Given
        PropertiesProvider propertiesProvider = new PropertiesProvider(Map.of("nlpParallelism", "1"));
        processPool = new PythonNlpWorkerPool(extensionService, propertiesProvider);
        // When
        Process p = null;
        try {
            p = new ProcessBuilder("sleep", "100000").start();
            Path pidPath = Files.createTempFile("datashare-extension-nlp-spacy-1.9.0", ".pid");
            dumpPid(pidPath.toFile(), p.pid());
            // When/Then
            assertThat(assertThrows(RuntimeException.class, () -> processPool.buildProcess().start()).getMessage())
                .matches("found phantom worker running in process.*");
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }
}
