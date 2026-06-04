package org.icij.datashare.cli.command;

import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class WorkerRunCommandTest extends AbstractDatashareCommandTest {

    @Test
    public void test_worker_run() {
        Properties props = parse("worker", "run");
        assertThat(props).includes(entry("mode", "TASK_WORKER"));
    }

    @Test
    public void test_worker_run_with_shared_options() {
        Properties props = parse("--redisAddress", "redis://my-redis:6379", "worker", "run");
        assertThat(props).includes(entry("mode", "TASK_WORKER"));
        assertThat(props).includes(entry("redisAddress", "redis://my-redis:6379"));
    }

    @Test
    public void test_worker_run_with_task_workers() {
        Properties props = parse("worker", "run", "--taskWorkers", "4");
        assertThat(props).includes(entry("mode", "TASK_WORKER"));
        assertThat(props).includes(entry("taskWorkers", "4"));
    }

    @Test
    public void test_worker_run_with_temporal_address() {
        Properties props = parse("--temporalAddress", "http://my-temporal:7233", "worker", "run");
        assertThat(props).includes(entry("temporalAddress", "http://my-temporal:7233"));
    }

    @Test
    public void test_worker_run_with_temporal_namespace() {
        Properties props = parse("--temporalNamespace", "my-ns", "worker", "run");
        assertThat(props).includes(entry("temporalNamespace", "my-ns"));
    }
}
