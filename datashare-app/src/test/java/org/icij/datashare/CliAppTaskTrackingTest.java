package org.icij.datashare;

import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_QUEUE_TYPE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_REPOSITORY_OPT;

public class CliAppTaskTrackingTest {
    @Test
    public void test_cannot_track_spawned_tasks_with_temporal_queue_and_memory_repository() {
        // Remote Temporal workers insert the tasks they spawn into THEIR repository;
        // with a process-local MEMORY repository this run can never see them.
        Properties properties = new Properties();
        properties.setProperty(BATCH_QUEUE_TYPE_OPT, "TEMPORAL");
        properties.setProperty(TASK_REPOSITORY_OPT, "MEMORY");

        assertThat(CliApp.canTrackSpawnedTasks(properties)).isFalse();
    }

    @Test
    public void test_can_track_spawned_tasks_with_temporal_queue_and_default_database_repository() {
        Properties properties = new Properties();
        properties.setProperty(BATCH_QUEUE_TYPE_OPT, "TEMPORAL");

        assertThat(CliApp.canTrackSpawnedTasks(properties)).isTrue();
    }

    @Test
    public void test_can_track_spawned_tasks_with_default_memory_queue() {
        // The default MEMORY batch queue executes everything in-process, so even a
        // MEMORY repository observes every spawned task.
        Properties properties = new Properties();
        properties.setProperty(TASK_REPOSITORY_OPT, "MEMORY");

        assertThat(CliApp.canTrackSpawnedTasks(properties)).isTrue();
    }
}
