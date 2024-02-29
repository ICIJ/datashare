package org.icij.datashare.tasks;

import ch.qos.logback.classic.Level;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.LogbackCapturingRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;

public class TaskManagerMemoryTest {
    @Rule public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();
    private final TaskManagerMemory taskManager= new TaskManagerMemory(new PropertiesProvider(), new ArrayBlockingQueue<>(3));

    @Test
    public void test_run_task() {
        TaskView<String> t = taskManager.startTask(() -> "run");
        taskManager.waitTasksToBeDone(100, TimeUnit.MILLISECONDS);
        assertThat(taskManager.getTask(t.id).getResult()).isEqualTo("run");
        assertThat(taskManager.getTask(t.id).getState()).isEqualTo(TaskView.State.DONE);
    }

    @Test
    public void test_stop_task() {
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

    @Test
    public void test_progress_on_unknown_task() {
        taskManager.progress("unknownId", 0.5);
        assertThat(logbackCapturingRule.logs(Level.WARN)).contains("unknown task id <unknownId> for progress=0.5 call");
    }

    @Test
    public void test_result_on_unknown_task() {
        taskManager.result("unknownId", 0.5);
        assertThat(logbackCapturingRule.logs(Level.WARN)).contains("unknown task id <unknownId> for result=0.5 call");
    }

    @After
    public void tearDown() { taskManager.shutdownNow();}
}
