package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class MemoryTaskManagerTest {
    private final TaskManagerMemory taskManager= new TaskManagerMemory(new PropertiesProvider());

    @Test
    public void test_run_task() {
        FutureTask<String> t = taskManager.startTask(() -> "run");
        taskManager.waitTasksToBeDone(100, TimeUnit.MILLISECONDS);
        assertThat(taskManager.get(t.toString()).getResult()).isEqualTo("run");
        assertThat(taskManager.get(t.toString()).state).isEqualTo(TaskView.State.DONE);
    }

    @Test
    public void test_stop_task() {
        FutureTask<String> t = taskManager.startTask(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                 return "interrupted";
            }
            return "run";});
        taskManager.stopTask(t.toString());
        assertThat(taskManager.get(t.toString()).state).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.get(t.toString()).getResult()).isNull();
    }

    @Test
    public void test_get_tasks() {
        FutureTask<String> t1 = taskManager.startTask(() -> "task 1");
        FutureTask<String> t2 = taskManager.startTask(() -> "task 2");

        assertThat(taskManager.getTasks()).contains(t1, t2);
    }

    @Test
    public void test_callback() throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        FutureTask<String> t1 = taskManager.startTask(() -> "task", l::countDown);
        l.await(1, SECONDS);

        assertThat(l.getCount()).isEqualTo(0);
        assertThat(taskManager.get(t1.toString()).getResult()).isEqualTo("task");
    }

    @Test
    public void test_create_task_with_properties() {
        MonitorableFutureTask<String> task = taskManager.startTask(() -> "task", new HashMap<String, Object>() {{ put("foo", "bar"); }});
        assertThat(task.properties).includes(entry("foo", "bar"));
    }

    @After
    public void tearDown() { taskManager.shutdownNow();}
}
