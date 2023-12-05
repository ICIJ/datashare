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
        AmqpConsumer<TestEvent, TestEventSaver> consumer = new AmqpConsumer<>(amqp, new TestEventSaver(), AmqpQueue.EVENT, TestEvent.class);
        consumer.consumeEvents();

        amqp.publish(AmqpQueue.EVENT, new TestEvent("hello AMQP"));

        assertThat(eventQueue.take().field).isEqualTo("hello AMQP");
        consumer.cancel();
    }

    @Test(timeout = 2000)
    public void test_publish_receive_2_events() throws Exception {
        AmqpConsumer<TestEvent, TestEventSaver> consumer = new AmqpConsumer<>(amqp, new TestEventSaver(), AmqpQueue.EVENT, TestEvent.class );
        consumer.consumeEvents(2);

        amqp.publish(AmqpQueue.EVENT, new TestEvent("hello 1"));
        amqp.publish(AmqpQueue.EVENT, new TestEvent("hello 2"));

        assertThat(eventQueue.take().field).isEqualTo("hello 1");
        assertThat(eventQueue.take().field).isEqualTo("hello 2");
        while (!consumer.isCanceled()) {
            Thread.sleep(100);
        }
        Assertions.assertThat(consumer.isCanceled()).isTrue();
    }

    @Test(timeout = 2000)
    public void test_publish_fanout_exchange() throws Exception {
        AmqpConsumer<TestEvent, TestEventSaver> consumer1 = new AmqpConsumer<>(amqp, new TestEventSaver(), AmqpQueue.EVENT, TestEvent.class );
        AmqpConsumer<TestEvent, TestEventSaver> consumer2 = new AmqpConsumer<>(amqp, new TestEventSaver(), AmqpQueue.EVENT, TestEvent.class );
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
        AmqpConsumer<TestEvent, TestEventSaver> consumer = new AmqpConsumer<>(amqp, new TestEventSaver(), AmqpQueue.EVENT, TestEvent.class);
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

    static class TestEventSaver implements EventSaver<TestEvent> {
        @Override
        public void save(TestEvent event) {
            eventQueue.add(event);
        }
    }
}
