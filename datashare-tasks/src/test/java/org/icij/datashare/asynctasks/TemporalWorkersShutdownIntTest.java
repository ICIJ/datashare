package org.icij.datashare.asynctasks;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.Task.State.DONE;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.WORKFLOWS_DEFAULT;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.icij.datashare.asynctasks.shutdownfixtures.InterruptibleActivityImpl;
import org.icij.datashare.asynctasks.shutdownfixtures.InterruptibleTaskFactory;
import org.icij.datashare.asynctasks.shutdownfixtures.InterruptibleWorkflowImpl;
import org.icij.datashare.asynctasks.temporal.TemporalRule;
import org.icij.datashare.asynctasks.temporal.TemporalWorkerOptions;
import org.icij.datashare.asynctasks.temporal.TemporalWorkers;
import org.icij.datashare.asynctasks.temporal.WorkflowRegistry;
import org.icij.datashare.user.User;
import org.junit.ClassRule;
import org.junit.Test;

public class TemporalWorkersShutdownIntTest {
    @ClassRule
    public static TemporalRule temporal = new TemporalRule();

    @Test(timeout = 60_000)
    public void test_activity_interrupted_by_the_shutdown_timeout_is_retried_by_another_worker() throws Exception {
        InterruptibleTaskFactory taskFactory = new InterruptibleTaskFactory();
        InterruptibleActivityImpl activity = new InterruptibleActivityImpl(taskFactory, temporal.interlocutor(),
                                                                           temporal.taskRepository(), 1.0d);
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.registerWorkflow(InterruptibleWorkflowImpl.class, WORKFLOWS_DEFAULT);
        registry.registerActivity(activity, WORKFLOWS_DEFAULT);
        TemporalWorkerOptions options = new TemporalWorkerOptions(1, Duration.ofMillis(200));
        TaskManagerTemporal taskManager = temporal.taskManager();

        Closeable shuttingDownWorkers = new TemporalWorkers(temporal.interlocutor()).start(
                registry, List.of(WORKFLOWS_DEFAULT), options);
        String taskId = taskManager.startTask("interruptible", User.local(), Map.of());
        assertThat(taskFactory.firstAttemptStarted.await(10, TimeUnit.SECONDS)).isTrue();

        shuttingDownWorkers.close(); // waits 200ms for the activity, then shutdownNow() interrupts it

        try (Closeable otherWorkers = new TemporalWorkers(temporal.interlocutor()).start(
                registry, List.of(WORKFLOWS_DEFAULT), options)) {
            taskManager.awaitTermination(40, TimeUnit.SECONDS);
            Task<?> task = taskManager.getTask(taskId);

            assertThat(task.getState()).as("state=" + task.getState() + ", error=" + task.getError()
                                                   + ", attempts=" + taskFactory.attempts.get()).isEqualTo(DONE);
            assertThat(taskFactory.attempts.get()).isEqualTo(2);
        }
    }
}
