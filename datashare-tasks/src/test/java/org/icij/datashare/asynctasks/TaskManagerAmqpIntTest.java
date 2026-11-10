package org.icij.datashare.asynctasks;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.AmqpInterlocutor;
import org.icij.datashare.asynctasks.bus.amqp.AmqpQueue;
import org.icij.datashare.tasks.RoutingStrategy;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.event.Level;

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
    @Rule public LogbackCapturingRule logbackCapturingRule = new LogbackCapturingRule();

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
        workersDieUntilTaskRunsOutOfRetries();

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
        workersDieUntilTaskRunsOutOfRetries();

        // THEN the task is still in finished state
        assertThat(nextMessage.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(taskManager.getTask(taskId).getState()).isEqualTo(Task.State.DONE);
    }

    @Test
    public void test_deleted_task_is_dropped_with_a_warning() throws Exception {
        // GIVEN a deleted task
        String taskId = taskManager.startTask("task", User.local(), new HashMap<>());
        repository.delete(taskId);

        // WHEN its message runs out of retries
        workersDieUntilTaskRunsOutOfRetries();

        // THEN it is dropped with a warning instead of an error
        assertThat(waitForLog(Level.WARN, taskId)).isTrue();
        assertThat(logbackCapturingRule.logs(Level.ERROR)).isEmpty();
    }

    private boolean waitForLog(Level level, String content) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (logbackCapturingRule.logs(level).stream().anyMatch(log -> log.contains(content))) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private void workersDieUntilTaskRunsOutOfRetries() throws Exception {
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
    }
}
