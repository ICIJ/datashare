package org.icij.datashare.asynctasks;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.fest.assertions.Assertions.assertThat;

public class TaskWorkerLoopAmqpIntTest {
    private static final AmqpQueue[] QUEUES = {AmqpQueue.TASK, AmqpQueue.MANAGER_EVENT, AmqpQueue.WORKER_EVENT};
    private final String amqpUri = EnvUtils.resolveUri("amqp", "amqp://guest:guest@amqp");
    private final ConnectionFactory connectionFactory = new ConnectionFactory();
    private AmqpInterlocutor amqp;
    private TaskManagerAmqp taskManager;
    private TaskSupplierAmqp supplier;

    @Before
    public void setUp() throws Exception {
        connectionFactory.setUri(amqpUri);
        amqp = new AmqpInterlocutor(new PropertiesProvider(Map.of("messageBusAddress", amqpUri)), QUEUES);
        amqp.deleteQueues(QUEUES);
        taskManager = new TaskManagerAmqp(amqp, new TaskRepositoryMemory(), RoutingStrategy.UNIQUE, () -> {});
        supplier = new TaskSupplierAmqp(amqp);
    }

    @After
    public void tearDown() throws Exception {
        supplier.close();
        taskManager.close();
        amqp.deleteQueues(QUEUES);
        amqp.close();
    }

    @Test(timeout = 10_000)
    public void test_graceful_shutdown_acks_the_finished_task_before_the_channel_is_closed() throws Exception {
        // the ack is sent once the callback returns, which is after the worker released its task: the broker must
        // not redeliver a task that is done because the channel was closed in between. The slow return from
        // handle() stands for whatever delays the ack (GC pause, scheduling)
        TaskWorkerLoop worker = new TaskWorkerLoop(new TestFactory(), supplier) {
            @Override
            void handle(Task<?> task) {
                super.handle(task);
                sleep(300);
            }
        };
        startWorker(worker);
        purgeTasks();
        taskManager.startTask(TestFactory.Sleep.class.getName(), User.local(), Map.of("duration", 10));
        waitUntil(() -> worker.currentTask.get() != null);

        worker.stopAcceptingTasks();
        worker.awaitCurrentTask(Duration.ofSeconds(5));
        worker.close();
        Thread.sleep(100); // let the broker process the channel closing

        assertThat(taskQueueStats().getMessageCount()).isEqualTo(0);
    }

    @Test(timeout = 10_000)
    public void test_stop_accepting_tasks_unsubscribes_every_consumer_of_the_shared_supplier() throws Exception {
        List<TaskWorkerLoop> workers = startWorkers(supplier, 2);
        waitUntil(() -> taskQueueStats().getConsumerCount() == 2);

        workers.forEach(TaskWorkerLoop::stopAcceptingTasks);

        assertThat(taskQueueStats().getConsumerCount()).isEqualTo(0);
    }

    private List<TaskWorkerLoop> startWorkers(TaskSupplierAmqp taskSupplier, int nb) {
        return IntStream.range(0, nb).mapToObj(i -> startWorker(new TaskWorkerLoop(new TestFactory(), taskSupplier)))
                        .toList();
    }

    private static TaskWorkerLoop startWorker(TaskWorkerLoop worker) {
        Thread thread = new Thread(worker::call);
        thread.setDaemon(true);
        thread.start();
        return worker;
    }

    // close() interrupts the delivery thread: the delay has to outlast that to stand for a late ack
    private static void sleep(long millis) {
        long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        boolean interrupted = false;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            try {
                Thread.sleep(Math.max(1, remaining / 1_000_000));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void purgeTasks() throws Exception {
        try (Connection connection = connectionFactory.newConnection(); Channel channel = connection.createChannel()) {
            channel.queuePurge(AmqpQueue.TASK.name());
        }
    }

    private AMQP.Queue.DeclareOk taskQueueStats() throws Exception {
        try (Connection connection = connectionFactory.newConnection(); Channel channel = connection.createChannel()) {
            return channel.queueDeclarePassive(AmqpQueue.TASK.name());
        }
    }

    private static void waitUntil(ThrowingBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in 5s");
            }
            Thread.sleep(5);
        }
    }

    @FunctionalInterface
    private interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
