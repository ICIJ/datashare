package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.fest.assertions.Assertions;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
        amqp = new AmqpInterlocutor(new Configuration("localhost", 12345, "admin", "admin", 10));
        amqp.createAmqpChannelForPublish(AmqpQueue.EVENT);
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

        amqp.publish(AmqpQueue.EVENT, new TestErrorEvent(new RuntimeException("my error")));

        TestErrorEvent expected = errorEventQueue.poll(2, TimeUnit.SECONDS);
        assert expected != null;
        assertThat(expected.error.getMessage()).isEqualTo("my error");
        assertThat(expected.error.getClass()).isEqualTo(RuntimeException.class);
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
        Assertions.assertThat(consumer.isCanceled()).isTrue();
    }

    @Test(timeout = 2000)
    public void test_publish_fanout_exchange() throws Exception {
        AmqpConsumer<TestEvent, TestEventConsumer> consumer1 = new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.EVENT, TestEvent.class );
        AmqpConsumer<TestEvent, TestEventConsumer> consumer2 = new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.EVENT, TestEvent.class );
        consumer1.consumeEvents(1);
        consumer2.consumeEvents(1);

        amqp.publish(AmqpQueue.EVENT, new TestEvent("hello pubsub"));

        assertThat(eventQueue.take().field).isEqualTo("hello pubsub");
        assertThat(eventQueue.take().field).isEqualTo("hello pubsub");
    }

    @Ignore("throws com.rabbitmq.client.AlreadyClosedException " +
            "the shutdown is too 'graceful' to reproduce a network error or server crash. " +
            "We don't know for now how to interrupt the QPid Server")
    @Test
    public void test_publish_with_broker_down() throws Exception {
        AmqpInterlocutor amqpInterlocutor = new AmqpInterlocutor(new Configuration("localhost", 12345, "admin", "admin", 10));
        AmqpConsumer<TestEvent, TestEventConsumer> consumer = new AmqpConsumer<>(amqp, new TestEventConsumer(), AmqpQueue.EVENT, TestEvent.class);
        consumer.consumeEvents();

        assertThat(qpid.amqpServer.shutdown());
        amqpInterlocutor.publish(AmqpQueue.EVENT, new TestEvent("hi!"));
        qpid.amqpServer.start();

        assertThat(eventQueue.take().field).isEqualTo("hi!");
        consumer.cancel();
    }

    @After
    public void tearDown() throws Exception {
        eventQueue.clear();
    }

    @AfterClass
    public static void afterClass() throws Exception {
        amqp.closeChannelsAndConnection();
    }

    static class TestEvent extends Event {
        public final String field;
        @JsonCreator
        public TestEvent(@JsonProperty("field") String field) {
            this.field = field;
        }
    }

    static class TestErrorEvent extends Event {
        public final Throwable error;
        @JsonCreator
        public TestErrorEvent(@JsonProperty("error") Throwable error) {
            this.error = error;
        }
    }

    static class TestEventConsumer implements Consumer<TestEvent> {
        @Override
        public void accept(TestEvent event) {
            eventQueue.add(event);
        }
    }
}
