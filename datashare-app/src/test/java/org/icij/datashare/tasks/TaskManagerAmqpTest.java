package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.com.bus.amqp.AmqpQueue;
import org.icij.datashare.com.bus.amqp.AmqpServerRule;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;

public class TaskManagerAmqpTest {
    private static AmqpInterlocutor AMQP;
    @ClassRule static public AmqpServerRule qpid = new AmqpServerRule(5672);
    TaskManagerAmqp taskManager;
    TaskSupplierAmqp taskSupplier;
    CountDownLatch nextMessage;

    @Test(timeout = 2000)
    public void test_new_task() throws Exception {
        TaskView<Serializable> expectedTaskView = taskManager.startTask("taskName", User.local(), new HashMap<>() {{
            put("key", "value");
        }});

        assertThat(taskManager.getTask(expectedTaskView.id)).isNotNull();
        TaskView<Serializable> actualTaskView = taskSupplier.get(10, TimeUnit.SECONDS);
        assertThat(actualTaskView).isNotNull();
        assertThat(actualTaskView).isEqualTo(expectedTaskView);
    }

    @Test(timeout = 2000)
    public void test_new_task_two_workers() throws Exception {
        try (TaskSupplierAmqp otherConsumer = new TaskSupplierAmqp(AMQP)) {
            taskManager.startTask("taskName1", User.local(), new HashMap<>());
            taskManager.startTask("taskName2", User.local(), new HashMap<>());

            TaskView<Serializable> actualTask1 = taskSupplier.get(2, TimeUnit.SECONDS);
            TaskView<Serializable> actualTask2 = otherConsumer.get(2, TimeUnit.SECONDS);

            assertThat(actualTask1).isNotNull();
            assertThat(actualTask2).isNotNull();
        }
    }

    @Test(timeout = 2000)
    public void test_task_progress() throws Exception {
        taskManager.startTask("taskName", User.local(), new HashMap<>());

        // in the task runner loop
        TaskView<Serializable> taskView = taskSupplier.get(10, TimeUnit.SECONDS);
        taskSupplier.progress(taskView.id,0.5);

        nextMessage.await();
        assertThat(taskManager.getTask(taskView.id).getProgress()).isEqualTo(0.5);
    }

    @Test(timeout = 2000)
    public void test_task_result() throws Exception {
        taskManager.startTask("taskName", User.local(), new HashMap<>());

        // in the task runner loop
        TaskView<Serializable> taskView = taskSupplier.get(10, TimeUnit.SECONDS);
        taskSupplier.result(taskView.id,"result");

        nextMessage.await();
        assertThat(taskManager.getTask(taskView.id).getState()).isEqualTo(TaskView.State.DONE);
        assertThat(taskManager.getTask(taskView.id).getResult()).isEqualTo("result");
    }

    @Test(timeout = 2000)
    public void test_task_error() throws Exception {
        taskManager.startTask("taskName", User.local(), new HashMap<>());

        // in the task runner loop
        TaskView<Serializable> taskView = taskSupplier.get(10, TimeUnit.SECONDS);
        taskSupplier.error(taskView.id,new RuntimeException("error in runner"));

        nextMessage.await();
        assertThat(taskManager.getTask(taskView.id).getResult()).isNull();
        assertThat(taskManager.getTask(taskView.id).getState()).isEqualTo(TaskView.State.ERROR);
        assertThat(taskManager.getTask(taskView.id).error.getMessage()).isEqualTo("error in runner");
    }

    @Test(timeout = 2000)
    public void test_task_canceled() throws Exception {
        taskManager.startTask("taskName", User.local(), new HashMap<>());

        // in the task runner loop
        TaskView<Serializable> taskView = taskSupplier.get(10, TimeUnit.SECONDS);
        taskSupplier.canceled(taskView,false);

        nextMessage.await();
        assertThat(taskManager.getTask(taskView.id).getProgress()).isEqualTo(0.0);
        assertThat(taskManager.getTask(taskView.id).getState()).isEqualTo(TaskView.State.CANCELLED);
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        AMQP = new AmqpInterlocutor(new PropertiesProvider(new HashMap<>() {{
            put("messageBusAddress", "amqp://admin:admin@localhost?deadLetter=false");
        }}));
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK);
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK_RESULT);
        AMQP.createAmqpChannelForPublish(AmqpQueue.EVENT);
    }

    @Before
    public void setUp() throws IOException {
        nextMessage = new CountDownLatch(1);
        taskManager = new TaskManagerAmqp(AMQP,
                new RedissonClientFactory().withOptions(Options.from(new PropertiesProvider().getProperties())).create(),
                () -> nextMessage.countDown());
        taskSupplier = new TaskSupplierAmqp(AMQP);
    }

    @After
    public void tearDown() throws Exception {
        taskManager.clear();
        taskManager.stopAllTasks(User.local());
        taskSupplier.close();
        taskManager.close();
    }

    @AfterClass
    public static void afterClass() throws Exception {
        AMQP.close();
    }
}