package org.icij.datashare.process;

import org.icij.datashare.OsArchDetector;
import org.icij.datashare.test.LogbackCapturingRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.Optional;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assume.assumeFalse;

public class ProcessTest {
    // Polling budget for process state changes: 100 attempts every 50ms, i.e. up to 5 seconds.
    private static final int MAX_POLL_ATTEMPTS = 100;
    private static final int POLL_INTERVAL_MS = 50;

    @Rule public LogbackCapturingRule logs = new LogbackCapturingRule();

    @Test
    public void test_launch_and_kill_process() {
        Process process = new Process(System.getProperty("user.dir"), "java-test", new String[]{"java"}, 12345);
        process.start();
        assertThat(process.isAlive()).isTrue();
        process.kill();
    }

    @Test
    public void test_close_kills_process_and_its_descendants() throws Exception {
        // Like the elasticsearch launcher, this process's real long-lived work lives in a
        // descendant: the shell spawns `sleep` as a child. Closing the Process must kill the
        // whole tree, otherwise the descendant is orphaned and keeps running.
        assumeFalse(new OsArchDetector().isWindows());
        Process process = new Process(System.getProperty("user.dir"), "tree-test",
                new String[]{"sh", "-c", "sleep 30 & wait"}, 12345);
        process.start();

        ProcessHandle descendant = waitForDescendant(process);
        assertThat(descendant.isAlive()).isTrue();

        process.close();

        assertThat(waitUntilDead(descendant)).isTrue();
    }

    private ProcessHandle waitForDescendant(Process process) throws InterruptedException {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Optional<ProcessHandle> descendant = ProcessHandle.of(process.pid())
                    .flatMap(handle -> handle.descendants().findFirst());
            if (descendant.isPresent()) {
                return descendant.get();
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("descendant process never started");
    }

    private boolean waitUntilDead(ProcessHandle handle) throws InterruptedException {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            if (!handle.isAlive()) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return false;
    }
}
