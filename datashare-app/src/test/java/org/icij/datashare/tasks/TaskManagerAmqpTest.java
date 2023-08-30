package org.icij.datashare.tasks;

import org.icij.datashare.com.bus.amqp.AmqpServerRule;
import org.junit.ClassRule;
import org.junit.Test;

public class TaskManagerAmqpTest {
    @ClassRule static public AmqpServerRule qpid = new AmqpServerRule(5672);
    TaskManagerAmqp taskManager = new TaskManagerAmqp("localhost", 5672);

    @Test
    public void test_new_task() {
        // TODO
    }
}
