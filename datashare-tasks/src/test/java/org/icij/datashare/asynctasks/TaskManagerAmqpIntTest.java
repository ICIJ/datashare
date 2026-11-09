package org.icij.datashare.asynctasks;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;


public class TaskManagerAmqpIntTest {
    private static final int MAX_DELIVERIES = 20;
    private final String amqpUri = EnvUtils.resolveUri("amqp", "amqp://guest:guest@amqp");
    private final ConnectionFactory workerConnectionFactory = new ConnectionFactory();
    private volatile CountDownLatch nextMessage = new CountDownLatch(1);
    private AmqpInterlocutor amqpInterlocutor;
    private final TaskRepositoryMemory repository = new TaskRepositoryMemory();
    private TaskManagerAmqp taskManager;
    private TaskSupplier taskSupplier;

    @Before
    public void setUp() throws Exception {
        amqpInterlocutor = new AmqpInterlocutor(new PropertiesProvider(Map.of("messageBusAddress", amqpUri)),
                new AmqpQueue[]{AmqpQueue.TASK, AmqpQueue.MANAGER_EVENT});
        amqpInterlocutor.deleteQueues(AmqpQueue.TASK, AmqpQueue.MANAGER_EVENT, AmqpQueue.TASK_DLQ);
        taskManager = new TaskManagerAmqp(amqpInterlocutor, repository, RoutingStrategy.UNIQUE,
                () -> nextMessage.countDown());
        taskSupplier = new TaskSupplierAmqp(amqpInterlocutor);
        workerConnectionFactory.setUri(amqpUri);
    }

    @After
    public void tearDown() throws Exception {
        taskSupplier.close();
        taskManager.close();
        amqpInterlocutor.deleteQueues(AmqpQueue.TASK, AmqpQueue.MANAGER_EVENT, AmqpQueue.TASK_DLQ);
        amqpInterlocutor.close();
    }

    @Test
    public void test_task_out_of_retries_is_in_error() throws Exception {
        // GIVEN a started task
        String taskId = taskManager.startTask("task", User.local(), new HashMap<>());

        // WHEN its workers keep dying until it runs out of retries
        int deliveries = 0;
        GetResponse delivery;
        do {
            try (Connection workerConnection = workerConnectionFactory.newConnection()) {
                delivery = workerConnection.createChannel().basicGet(AmqpQueue.TASK.name(), false);
                if (delivery != null) {
                    deliveries++;
                    taskSupplier.progress(taskId, 0.5);
                    assertThat(nextMessage.await(5, TimeUnit.SECONDS)).isTrue();
                    nextMessage = new CountDownLatch(1);
                }
            }
        } while (delivery != null && deliveries < MAX_DELIVERIES);
        assertThat(deliveries).isLessThan(MAX_DELIVERIES);

        // THEN the task is in error state
        assertThat(nextMessage.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(taskManager.getTask(taskId).getState()).isEqualTo(Task.State.ERROR);
    }

    @Test
    public void test_task_already_finished_is_not_in_error() throws Exception {
        // GIVEN an already finished task
        String taskId = taskManager.startTask("task", User.local(), new HashMap<>());
        Task<Integer> task = repository.getTask(taskId);
        task.setResult(new TaskResult<>(12));
        repository.update(task);

        // WHEN its message runs out of retries
        int deliveries = 0;
        GetResponse delivery;
        do {
            try (Connection workerConnection = workerConnectionFactory.newConnection()) {
                delivery = workerConnection.createChannel().basicGet(AmqpQueue.TASK.name(), false);
                if (delivery != null) {
                    deliveries++;
                }
            }
        } while (delivery != null && deliveries < MAX_DELIVERIES);
        assertThat(deliveries).isLessThan(MAX_DELIVERIES);

        // THEN the task is still in finished state
        assertThat(nextMessage.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(taskManager.getTask(taskId).getState()).isEqualTo(Task.State.DONE);
    }
}
