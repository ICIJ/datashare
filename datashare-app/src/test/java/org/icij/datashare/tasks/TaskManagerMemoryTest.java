package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class TaskManagerMemoryTest {
    private final TaskManagerMemory taskManager= new TaskManagerMemory(new PropertiesProvider());

    @Test
    public void test_run_task() throws Exception {
        TaskView<String> t = taskManager.startTask(() -> "run");
        taskManager.waitTasksToBeDone(100, TimeUnit.MILLISECONDS);
        assertThat(taskManager.get(t.name).getResult()).isEqualTo("run");
        assertThat(taskManager.get(t.name).getState()).isEqualTo(TaskView.State.DONE);
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
        taskManager.stopTask(t.name);
        assertThat(taskManager.get(t.name).getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.get(t.name).getResult()).isNull();
    }

    @Test
    public void test_get_tasks() throws Exception {
        TaskView<String> t1 = taskManager.startTask(() -> "task 1");
        TaskView<String> t2 = taskManager.startTask(() -> "task 2");

        assertThat(taskManager.get()).hasSize(2);
    }

    @Test
    public void test_callback() throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        TaskView<String> t1 = taskManager.startTask(() -> "task", l::countDown);
        l.await(1, SECONDS);

        assertThat(l.getCount()).isEqualTo(0);
        assertThat(taskManager.get(t1.name).getResult()).isEqualTo("task");
    }

    @Test
    public void test_create_task_with_properties() {
        TaskView<String> taskView = taskManager.startTask(() -> "task", new HashMap<String, Object>() {{ put("foo", "bar"); }});
        assertThat(taskView.task.properties).includes(entry("foo", "bar"));
    }

    @After
    public void tearDown() { taskManager.shutdownNow();}
}
