package org.icij.datashare;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;

public class TaskManagerTest {
    private TaskManager taskManager= new TaskManager(new PropertiesProvider());

    @Test
    public void test_run_task() throws Exception {
        FutureTask<String> t = taskManager.startTask(() -> "run");
        assertThat(taskManager.getTask(t.hashCode()).get()).isEqualTo("run");
        assertThat(taskManager.getTask(t.hashCode()).isDone()).isTrue();
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
        assertThat(taskManager.getTask(t1.hashCode()).get()).isEqualTo("task");
    }

    @After
    public void tearDown() { taskManager.shutdownNow();}
}