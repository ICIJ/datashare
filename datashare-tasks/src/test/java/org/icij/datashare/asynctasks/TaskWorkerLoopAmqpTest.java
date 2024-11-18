package org.icij.datashare.asynctasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.asynctasks.bus.amqp.AmqpServerRule;
import org.icij.datashare.asynctasks.bus.amqp.ShutdownEvent;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.HashMap;

public class TaskWorkerLoopAmqpTest {
    private static AmqpInterlocutor AMQP;
    TestFactory registry = new TestFactory();
    @ClassRule static public AmqpServerRule qpid = new AmqpServerRule(5672);
    TaskSupplierAmqp taskSupplier;

    @Test(timeout = 5000)
    public void test_shutdown() throws Exception {
        TaskWorkerLoop worker = new TaskWorkerLoop(registry, taskSupplier);
        Thread appThread = new Thread(worker::call);
        appThread.start();
        AMQP.publish(AmqpQueue.WORKER_EVENT, new ShutdownEvent());
        appThread.join();
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        AMQP = new AmqpInterlocutor(new PropertiesProvider(new HashMap<>() {{
            put("messageBusAddress", "amqp://admin:admin@localhost?rabbitMq=false");
        }}));
        AMQP.createAmqpChannelForPublish(AmqpQueue.TASK);
        AMQP.createAmqpChannelForPublish(AmqpQueue.MANAGER_EVENT);
        AMQP.createAmqpChannelForPublish(AmqpQueue.WORKER_EVENT);
    }

    @Before
    public void setUp() throws Exception {
        taskSupplier= new TaskSupplierAmqp(AMQP);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        AMQP.close();
    }
}