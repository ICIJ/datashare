package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.asynctasks.NackException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.fest.assertions.Assertions.assertThat;

public class AmqpTest {
    static BlockingQueue<TestEvent> eventQueue = new ArrayBlockingQueue<>(10);
    @ClassRule
    static public AmqpServerRule qpid = new AmqpServerRule(12345);
    static private AmqpInterlocutor amqp;

    @BeforeClass
    public static void setUp() throws Exception {
        AmqpQueue[] queues = {AmqpQueue.EVENT, AmqpQueue.MANAGER_EVENT};
        amqp = new AmqpInterlocutor(new Configuration(new URI("amqp://admin:admin@localhost:12345?nbMessageMax=10&rabbitMq=false")), queues);
    }

    @Test
    public void test_publish_receive() throws Exception {
        AmqpConsumer<TestEvent, TestEventConsumer> consumer = new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.EVENT, TestEvent.class);
        consumer.consumeEvents();

        amqp.publish(AmqpQueue.EVENT, new TestEvent("hello AMQP"));

        assertThat(eventQueue.take().field).isEqualTo("hello AMQP");
        consumer.cancel();
    }

    @Test
    public void test_publish_receive_throwable() throws Exception {
        BlockingQueue<TestErrorEvent> errorEventQueue = new LinkedBlockingQueue<>();
        AmqpConsumer<TestErrorEvent, Consumer<TestErrorEvent>> consumer = new AmqpConsumer<>(amqp, errorEventQueue::add, AmqpQueue.EVENT, TestErrorEvent.class);
        consumer.consumeEvents();

        amqp.publish(AmqpQueue.EVENT, new TestErrorEvent(new TaskError(new RuntimeException("my error"))));

        TestErrorEvent expected = errorEventQueue.poll(2, TimeUnit.SECONDS);
        assert expected != null;
        assertThat(expected.error.getMessage()).isEqualTo("my error");
        assertThat(expected.error.getClass()).isEqualTo(TaskError.class);
        consumer.cancel();
    }

    @Test(timeout = 2000)
    public void test_publish_receive_2_events() throws Exception {
        AmqpConsumer<TestEvent, TestEventConsumer> consumer = new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.EVENT, TestEvent.class );
        consumer.consumeEvents(2);

        amqp.publish(AmqpQueue.EVENT, new TestEvent("hello 1"));
        amqp.publish(AmqpQueue.EVENT, new TestEvent("hello 2"));

        assertThat(eventQueue.take().field).isEqualTo("hello 1");
        assertThat(eventQueue.take().field).isEqualTo("hello 2");
        qpid.waitCancel(consumer);
        assertThat(consumer.isCanceled()).isTrue();
    }

    @Test(timeout = 6000)
    public void test_publish_fanout_exchange() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch init = new CountDownLatch(2);
        executorService.submit(() -> new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.EVENT, TestEvent.class, init).consumeEvents(1));
        executorService.submit(() -> new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.EVENT, TestEvent.class, init).consumeEvents(1));
        init.await(1, TimeUnit.SECONDS);

        amqp.publish(AmqpQueue.EVENT, new TestEvent("hello pubsub"));
        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(eventQueue.take().field).isEqualTo("hello pubsub");
        assertThat(eventQueue.take().field).isEqualTo("hello pubsub");
    }

    @Test(timeout = 2000)
    public void test_consume_nack_with_requeue() throws Exception {
        FailingConsumer failingConsumer = new FailingConsumer(true);
        new AmqpConsumer<>(amqp, failingConsumer, AmqpQueue.MANAGER_EVENT, TestEvent.class).consumeEvents(1);
        new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.MANAGER_EVENT, TestEvent.class).consumeEvents(1);

        amqp.publish(AmqpQueue.MANAGER_EVENT, new TestEvent("boom!!"));

        assertThat(eventQueue.take().field).isEqualTo("boom!!");
        assertThat(failingConsumer.hasBeenCalled).isTrue();
    }

    @Test
    public void test_consume_nack_without_requeue() throws Exception {
        FailingConsumer failingConsumer = new FailingConsumer(false);
        new AmqpConsumer<>(amqp, failingConsumer, AmqpQueue.EVENT, TestEvent.class ).consumeEvents(1);
        new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.MANAGER_EVENT, TestEvent.class).consumeEvents(1);

        amqp.publish(AmqpQueue.EVENT, new TestEvent("boom!!"));

        assertThat(eventQueue.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(failingConsumer.hasBeenCalled).isTrue();
    }

    @Test
    public void test_wait_channel_is_closed() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(1);

        AmqpConsumer<TestEvent, TestEventConsumer> c = new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.EVENT, TestEvent.class).consumeEvents(e -> {});
        executorService.submit(c::waitUntilChannelIsClosed);
        c.shutdown();

        executorService.shutdown();
        assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Ignore("throws com.rabbitmq.client.AlreadyClosedException " +
            "the shutdown is too 'graceful' to reproduce a network error or server crash. " +
            "We don't know for now how to interrupt the QPid Server")
    @Test
    public void test_publish_with_broker_down() throws Exception {
        AmqpInterlocutor amqpInterlocutor = new AmqpInterlocutor(new Configuration("localhost", 12345, "admin", "admin", 10), AmqpQueue.values());
        AmqpConsumer<TestEvent, TestEventConsumer> consumer = new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.EVENT, TestEvent.class);
        consumer.consumeEvents();

        assertThat(qpid.amqpServer.shutdown());
        amqpInterlocutor.publish(AmqpQueue.EVENT, new TestEvent("hi!"));
        qpid.amqpServer.start();

        assertThat(eventQueue.take().field).isEqualTo("hi!");
        consumer.cancel();
    }

    @Test(timeout = 4000)
    public void test_routing_with_suffix() throws Exception {
        String key = "KEY";
        AmqpConsumer<TestEvent, TestEventConsumer> consumer = new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.MANAGER_EVENT, TestEvent.class, key).consumeEvents(1);
        consumer.consumeEvents();

        amqp.publish(AmqpQueue.MANAGER_EVENT, key, new TestEvent("hello Key AMQP"));

        assertThat(eventQueue.take().field).isEqualTo("hello Key AMQP");
        consumer.cancel();
    }

    @After
    public void tearDown() throws Exception {
        eventQueue.clear();
    }

    @AfterClass
    public static void afterClass() throws Exception {
        amqp.close();
    }

    static class TestEvent extends Event {
        public final String field;
        @JsonCreator
        public TestEvent(@JsonProperty("field") String field) {
            this.field = field;
        }
    }

    static class TestErrorEvent extends Event {
        public final TaskError error;
        @JsonCreator
        public TestErrorEvent(@JsonProperty("error") TaskError error) {
            this.error = error;
        }
    }

    static class TestEventConsumer implements Consumer<TestEvent> {
        @Override
        public void accept(TestEvent event) {
            eventQueue.add(event);
        }
    }

    static class FailingConsumer implements Consumer<TestEvent> {
        public volatile boolean hasBeenCalled = false;
        public final boolean requeue;

        FailingConsumer(boolean requeue) {
            this.requeue = requeue;
        }

        @Override
        public void accept(TestEvent event) {
            hasBeenCalled = true;
            throw new NackException(new RuntimeException("consumer fails"), requeue);
        }
    }
}
