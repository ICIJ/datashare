package org.icij.datashare.asynctasks;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.user.User;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;


public class TaskManagerAmqpIntTest {
    private CountDownLatch nextMessage = new CountDownLatch(1);

    @Test
    public void test_task_out_of_retries_is_in_error() throws Exception {
        // GIVEN a started task
        String amqpUri = EnvUtils.resolveUri("amqp", "amqp://guest:guest@amqp");
        AmqpInterlocutor amqpInterlocutor = new AmqpInterlocutor(
                new PropertiesProvider(Map.of("messageBusAddress", amqpUri)),
                new AmqpQueue[]{AmqpQueue.TASK, AmqpQueue.MANAGER_EVENT});
        amqpInterlocutor.deleteQueues(AmqpQueue.TASK, AmqpQueue.MANAGER_EVENT, AmqpQueue.TASK_DLQ);
        TaskRepository taskRepository = new TaskRepositoryMemory();
        TaskManagerAmqp taskManager = new TaskManagerAmqp(amqpInterlocutor, taskRepository, RoutingStrategy.UNIQUE,
                () -> nextMessage.countDown());
        TaskSupplier taskSupplier = new TaskSupplierAmqp(amqpInterlocutor);
        String taskId = taskManager.startTask("task", User.local(), new HashMap<>());

        // WHEN its worker keep dying until running out of retries
        ConnectionFactory workerConnectionFactory = new ConnectionFactory();
        workerConnectionFactory.setUri(amqpUri);
        int maxDeliveries = 20;
        int deliveries = 0;
        GetResponse delivery;
        do {
            try (Connection workerConnection = workerConnectionFactory.newConnection()) {
                Channel workerChannel = workerConnection.createChannel();
                delivery = workerChannel.basicGet(AmqpQueue.TASK.name(), false);
                if (delivery != null) {
                    deliveries++;
                    taskSupplier.progress(taskId, 0.5);
                    assertThat(nextMessage.await(5, TimeUnit.SECONDS)).isTrue();
                    nextMessage = new CountDownLatch(1);
                }
            }
        } while (delivery != null && deliveries < maxDeliveries);
        assertThat(deliveries).isLessThan(maxDeliveries);

        // THEN the task is in error state
        assertThat(nextMessage.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(taskManager.getTask(taskId).getState()).isEqualTo(Task.State.ERROR);
    }
}
