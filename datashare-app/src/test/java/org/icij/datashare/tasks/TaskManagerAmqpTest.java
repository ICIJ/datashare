package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.bus.amqp.AmqpConsumer;
import org.icij.datashare.com.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.com.bus.amqp.AmqpQueue;
import org.icij.datashare.com.bus.amqp.AmqpServerRule;
import org.icij.datashare.com.bus.amqp.EventSaver;
import org.icij.datashare.com.bus.amqp.ProgressEvent;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;

public class TaskManagerAmqpTest {
    private static AmqpInterlocutor AMQP;
    @ClassRule static public AmqpServerRule qpid = new AmqpServerRule(5672);
    TaskManagerAmqp taskManager;
    TaskSupplierAmqp taskSupplier;

    @Test
    public void test_new_task() throws Exception {
        TaskView<Serializable> expectedTaskView = taskManager.startTask("taskName", User.local(), new HashMap<>() {{
            put("key", "value");
        }});

        assertThat(taskManager.getTask(expectedTaskView.id)).isNotNull();
        TaskView<Serializable> actualTaskView = taskSupplier.get(10, TimeUnit.SECONDS);
        assertThat(actualTaskView).isNotNull();
        assertThat(actualTaskView).isEqualTo(expectedTaskView);
    }

    @Test
    public void test_task_progress() throws Exception {
        taskManager.startTask("taskName", User.local(), new HashMap<>() {{
            put("key", "value");
        }});

        AmqpConsumer<ProgressEvent, EventSaver<ProgressEvent>> consumer = new AmqpConsumer<>(AMQP,
                event -> {}, AmqpQueue.EVENT, ProgressEvent.class);
        consumer.consumeEvents(1);

        // in the task runner loop
        TaskView<Serializable> taskView = taskSupplier.get(10, TimeUnit.SECONDS);
        taskSupplier.progress(taskView.id,0.5);
        System.out.println(taskSupplier.consumer);

        qpid.waitCancel(consumer);
        assertThat(taskManager.getTask(taskView.id).getProgress()).isEqualTo(0.5);
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        AMQP = new AmqpInterlocutor(new PropertiesProvider());
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK);
        AMQP.createAmqpChannelForPublish(AmqpQueue.EVENT);
    }

    @Before
    public void setUp() throws IOException {
        taskManager = new TaskManagerAmqp(AMQP, new RedissonClientFactory().withOptions(Options.from(new PropertiesProvider().getProperties())).create());
        taskSupplier = new TaskSupplierAmqp(AMQP);

    }

    @After
    public void tearDown() throws Exception {
        taskManager.stopAllTasks(User.local());
        taskManager.clearDoneTasks();
    }
    public TaskManagerAmqpTest() throws IOException {}
}