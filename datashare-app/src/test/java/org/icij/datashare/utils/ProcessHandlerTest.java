package org.icij.datashare.utils;

import static java.lang.String.join;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.utils.ProcessHandler.dumpPid;
import static org.icij.datashare.utils.ProcessHandler.findPidPaths;
import static org.icij.datashare.utils.ProcessHandler.isProcessRunning;
import static org.icij.datashare.utils.ProcessHandler.killProcessById;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

public class ProcessHandlerTest {
    private static final String pidFileDir = "/tmp";

    @Before
    public void setUp() throws Exception {
        Arrays.stream(
                Objects.requireNonNull(Path.of(pidFileDir)
                    .toFile()
                    .listFiles((ignored, name) -> name.matches("test-process-handler-.*\\.pid"))))
            .forEach(File::delete);
    }

    @Test(timeout = 10000)
    public void test_process_handler_int() throws IOException, InterruptedException {
        // Given
        int sleepDuration = 1000;
        ProcessBuilder builder = new ProcessBuilder(List.of("sleep", String.valueOf(sleepDuration)));
        Process childProcess = builder.start();

        Path tmpRoot = Path.of(FileSystems.getDefault().getSeparator(), pidFileDir);

        // When
        long pid = childProcess.pid();
        Path pidPath = Files.createTempFile(tmpRoot, "test-process-handler-", ".pid");
        File pidFile = pidPath.toFile();
        dumpPid(pidFile, pid);

        // Then
        assertThat(pidFile.exists()).isTrue();
        String pidContent = join("\n", Files.readAllLines(pidPath));
        String expectedContent = String.valueOf(pid);
        assertThat(pidContent).isEqualTo(expectedContent);

        // When
        String pidFilePattern = "glob:test-process-handler-*.pid";
        List<Path> foundPaths = findPidPaths(pidFilePattern, Path.of(pidFileDir));
        // Then
        assertThat(foundPaths.size()).isEqualTo(1);
        assertThat(foundPaths.get(0).toString()).isEqualTo(pidPath.toString());

        // When
        boolean isRunning = isProcessRunning(pid, 2, TimeUnit.SECONDS);
        // Then
        assertThat(isRunning).isTrue();
        // When
        boolean isRunningFromFile = isProcessRunning(pidPath, 2, TimeUnit.SECONDS);
        // Then
        assertThat(isRunningFromFile).isTrue();

        // When
        killProcessById(pid, true);
        boolean isRunningAfterKilled = isProcessRunning(pid, 2, TimeUnit.SECONDS);
        assertThat(isRunningAfterKilled).isFalse();
    }
}
