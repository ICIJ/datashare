package org.icij.datashare;

import org.junit.After;
import org.junit.Test;

import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;

public class TaskManagerTest {
    private TaskManager taskManager= new TaskManager(new PropertiesProvider());

    @Test
    public void test_run_task() throws Exception {
        int tId = taskManager.startTask(() -> "run");
        assertThat(taskManager.getTask(tId).get()).isEqualTo("run");
        assertThat(taskManager.getTask(tId).isDone()).isTrue();
    }

    @Test
    public void test_get_tasks() throws Exception {
        int tId1 = taskManager.startTask(() -> "task 1");
        int tId2 = taskManager.startTask(() -> "task 2");

        assertThat(taskManager.getTasks().stream().map(Object::hashCode).collect(toList())).contains(tId1, tId2);
    }

    @After
    public void tearDown() { taskManager.shutdownNow();}
}