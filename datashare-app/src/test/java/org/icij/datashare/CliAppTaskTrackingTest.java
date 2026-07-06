package org.icij.datashare;

import org.icij.datashare.asynctasks.TaskManagerAmqp;
import org.icij.datashare.asynctasks.TaskManagerMemory;
import org.icij.datashare.asynctasks.TaskManagerRedis;
import org.icij.datashare.asynctasks.TaskManagerTemporal;
import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_QUEUE_TYPE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.TASK_REPOSITORY_OPT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class CliAppTaskTrackingTest {
    @Test
    public void test_shutdown_stops_the_in_process_worker_pool() throws Exception {
        // The MEMORY task manager's worker pool lives in this JVM and belongs to
        // this run only: it must be shut down so the pool stops cleanly.
        TaskManagerMemory taskManager = mock(TaskManagerMemory.class);

        CliApp.shutdownLocalWorkers(taskManager);

        verify(taskManager).shutdown();
    }

    @Test
    public void test_shutdown_does_not_broadcast_to_a_shared_redis_bus() throws Exception {
        // TaskManagerRedis.shutdown() publishes a ShutdownEvent on the shared event
        // topic: every worker on the bus would close, including a concurrent server's
        // or another run's workers with QUEUED/RUNNING tasks.
        TaskManagerRedis taskManager = mock(TaskManagerRedis.class);

        CliApp.shutdownLocalWorkers(taskManager);

        verify(taskManager, never()).shutdown();
    }

    @Test
    public void test_shutdown_does_not_broadcast_to_a_shared_amqp_bus() throws Exception {
        TaskManagerAmqp taskManager = mock(TaskManagerAmqp.class);

        CliApp.shutdownLocalWorkers(taskManager);

        verify(taskManager, never()).shutdown();
    }

    @Test
    public void test_shutdown_leaves_temporal_workers_alone() throws Exception {
        // Temporal owns worker lifecycle; the CLI has nothing to stop.
        TaskManagerTemporal taskManager = mock(TaskManagerTemporal.class);

        CliApp.shutdownLocalWorkers(taskManager);

        verify(taskManager, never()).shutdown();
    }

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
