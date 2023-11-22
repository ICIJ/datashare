package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class TaskManagerMemoryTest {
    @Mock TaskFactory factory;
    private final TaskManagerMemory taskManager= new TaskManagerMemory(new PropertiesProvider(), factory);

    @Test
    public void test_run_task() throws Exception {
        TaskView<String> t = taskManager.startTask(() -> "run");
        taskManager.waitTasksToBeDone(100, TimeUnit.MILLISECONDS);
        assertThat(taskManager.getTask(t.id).getResult()).isEqualTo("run");
        assertThat(taskManager.getTask(t.id).getState()).isEqualTo(TaskView.State.DONE);
    }

    @Test
    public void test_stop_task() throws Exception {
        TaskView<String> t = taskManager.startTask(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                 return "interrupted";
            }
            return "run";});
        taskManager.stopTask(t.id);
        assertThat(taskManager.getTask(t.id).getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.getTask(t.id).getResult()).isNull();
    }

    @Test
    public void test_get_tasks() throws Exception {
        TaskView<String> t1 = taskManager.startTask(() -> "task 1");
        TaskView<String> t2 = taskManager.startTask(() -> "task 2");

        assertThat(taskManager.getTasks()).hasSize(2);
    }

    @Test
    public void test_callback() throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        TaskView<String> t1 = taskManager.startTask(() -> "task", l::countDown);
        l.await(1, SECONDS);

        assertThat(l.getCount()).isEqualTo(0);
        assertThat(taskManager.getTask(t1.id).getResult()).isEqualTo("task");
    }

    @Test
    public void test_clear_the_only_task() {
        TaskView<String> task = taskManager.startTask(() -> "task 1");
        assertThat(taskManager.getTasks()).hasSize(1);
        taskManager.clearTask(task.id);
        assertThat(taskManager.getTasks()).hasSize(0);
    }

    @Test
    public void test_clear_task_among_two_tasks() {
        TaskView<String> t1 = taskManager.startTask(() -> "task 1");
        TaskView<String> t2 = taskManager.startTask(() -> "task 2");
        assertThat(taskManager.getTasks()).hasSize(2);
        taskManager.clearTask(t1.id);
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(t1.id)).isNull();
        assertThat(taskManager.getTask(t2.id)).isNotNull();
    }

    @Test
    public void test_clear_and_return_the_same_task() {
        TaskView<String> t1 = taskManager.startTask(() -> "task 1");
        assertThat(taskManager.getTasks()).hasSize(1);
        TaskView<?> t2 = taskManager.clearTask(t1.id);
        assertThat(taskManager.getTasks()).hasSize(0);
        assertThat(t1.id).isEqualTo(t2.id);
    }

    @After
    public void tearDown() { taskManager.shutdownNow();}
}
