package org.icij.datashare;

import static org.fest.assertions.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

public class OpenMpEnvInheritanceTest {
    // Surefire (root pom) injects OMP_THREAD_LIMIT=1 into the forked test JVM.
    // This proves Datashare's OCR thread-limit default actually reaches the
    // test JVM environment. Run via `mvn test`; an IDE run without the maven
    // env will (correctly) fail this assertion.
    @Test
    public void test_surefire_sets_omp_thread_limit_on_jvm() {
        assertThat(System.getenv("OMP_THREAD_LIMIT")).isEqualTo("1");
        assertThat(System.getenv("OMP_WAIT_POLICY")).isEqualTo("passive");
    }

    // A child process spawned by this JVM inherits its environment: this is
    // exactly how Tika's TesseractOCRParser spawns tesseract, so an inherited
    // OMP_THREAD_LIMIT=1 reaches every OCR subprocess.
    @Test(timeout = 10000)
    public void test_spawned_child_inherits_omp_thread_limit() throws IOException, InterruptedException {
        // Relies on a POSIX `sh`; skip on platforms without one (e.g. Windows dev boxes).
        Assume.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Process child = new ProcessBuilder(List.of("sh", "-c", "echo $OMP_THREAD_LIMIT")).start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.readLine();
        }
        child.waitFor();
        assertThat(output).isEqualTo("1");
    }
}
