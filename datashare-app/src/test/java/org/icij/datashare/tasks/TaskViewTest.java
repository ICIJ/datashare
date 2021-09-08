package org.icij.datashare.tasks;

import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.fest.assertions.Assertions.assertThat;

public class TaskViewTest {
    private Executor executor = Executors.newSingleThreadExecutor();

    @Test
    public void test_get_result_sync_when_task_is_done() throws Exception {
        MonitorableFutureTask<String> task = new MonitorableFutureTask<>(() -> "run");
        task.run();
        TaskView<String> taskView = new TaskView<>(task);

        assertThat(taskView.getResult()).isEqualTo("run");
        assertThat(taskView.getProgress()).isEqualTo(1);
        assertThat(taskView.getState()).isEqualTo(TaskView.State.DONE);
    }

    @Test
    public void test_get_result_sync_when_task_is_running() throws Exception {
        MonitorableFutureTask<String> task = new MonitorableFutureTask<>(() -> {
            Thread.sleep(100);
            return "run";
        });
        executor.execute(task);
        TaskView<String> taskView = new TaskView<>(task);

        assertThat(taskView.getProgress()).isEqualTo(-2);
        assertThat(taskView.getState()).isEqualTo(TaskView.State.RUNNING);
        assertThat(taskView.getResult(false)).isNull();
        assertThat(taskView.getResult(true)).isEqualTo("run");
        assertThat(taskView.getState()).isEqualTo(TaskView.State.DONE);
    }

    @Test
    public void test_get_result_sync_when_task_is_not_local() throws Exception{
        TaskView<Object> taskView = new TaskView<>("task", TaskView.State.DONE, 1, User.local(), null, new HashMap<>());
        assertThat(taskView.getResult()).isNull();
        assertThat(taskView.getState()).isEqualTo(TaskView.State.DONE);
        assertThat(taskView.getProgress()).isEqualTo(1);
    }

    @Test
    public void test_get_result_sync_when_task_is_not_local_and_result_is_not_null() throws Exception{
        TaskView<Object> taskView = new TaskView<>("task", TaskView.State.DONE, 1, User.local(), "run", new HashMap<>());
        assertThat(taskView.getResult()).isEqualTo("run");
    }
}