package org.icij.datashare;

import org.junit.After;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class TaskManagerTest {
    private TaskManager taskManager= new TaskManager();

    @Test
    public void test_run_task() throws Exception {
        int tId = taskManager.startTask(() -> "run");
        assertThat(taskManager.getTask(tId).get()).isEqualTo("run");
        assertThat(taskManager.getTask(tId).isDone()).isTrue();
    }

    @After
    public void tearDown() { taskManager.shutdownNow();}
}