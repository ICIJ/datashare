package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import org.fest.assertions.Assertions;
import org.icij.datashare.com.bus.amqp.AmqpServerRule;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;

import static org.fest.assertions.Assertions.assertThat;

public class AmqpTest {
    static BlockingQueue<TestEvent> eventQueue = new ArrayBlockingQueue<>(10);
    @ClassRule
    static public AmqpServerRule qpid = new AmqpServerRule(12345);
    static private AmqpInterlocutor amqpInterlocutor;

    @BeforeClass
    public static void setUp() throws Exception {
        amqpInterlocutor = AmqpInterlocutor.initWith(new Configuration("localhost", 12345, "admin", "admin", 10));
        amqpInterlocutor.createAmqpChannelForPublish(AmqpQueue.EVENT);
    }

    @Test
    public void test_publish_receive() throws Exception {
        TestConsumer consumer = new TestConsumer(new TestEventSaver(), AmqpQueue.EVENT);
        consumer.consumeEvents();

        amqpInterlocutor.publish(AmqpQueue.EVENT, new TestEvent("hello AMQP"));

        assertThat(eventQueue.take().field).isEqualTo("hello AMQP");
        consumer.cancel();
    }

    @Test
    public void test_publish_receive_2_events() throws Exception {
        TestConsumer consumer = new TestConsumer(new TestEventSaver(), AmqpQueue.EVENT);
        consumer.consumeEvents(2);

        amqpInterlocutor.publish(AmqpQueue.EVENT, new TestEvent("hello 1"));
        amqpInterlocutor.publish(AmqpQueue.EVENT, new TestEvent("hello 2"));

        assertThat(eventQueue.take().field).isEqualTo("hello 1");
        assertThat(eventQueue.take().field).isEqualTo("hello 2");
        for (int i=0; i<30 && !consumer.isCanceled(); i++) {
            Thread.sleep(100);
        }
        Assertions.assertThat(consumer.isCanceled()).isTrue();
    }

    @Ignore("throws com.rabbitmq.client.AlreadyClosedException " +
            "the shutdown is too 'graceful' to reproduce a network error or server crash. " +
            "We don't know for now how to interrupt the QPid Server")
    @Test
    public void test_publish_with_broker_down() throws Exception {
        AmqpInterlocutor amqpInterlocutor = AmqpInterlocutor.initWith(new Configuration("localhost", 12345, "admin", "admin", 10));
        TestConsumer consumer = new TestConsumer(new TestEventSaver(), AmqpQueue.EVENT);
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
    static public void tearDownClass() throws IOException, TimeoutException {
        amqpInterlocutor.close();
    }

    static class TestConsumer extends AbstractConsumer<TestEvent, TestEventSaver> {
        public TestConsumer(TestEventSaver eventSaver, AmqpQueue queue) throws IOException, TimeoutException {super(eventSaver, queue);}
        @Override
        public TestEvent deserialize(byte[] rawJson) throws IOException {return JsonObjectMapper.MAPPER.readValue(rawJson, new TypeReference<>() {});}
    }

    static class TestEvent extends Event {
        public final String field;
        @JsonCreator
        public TestEvent(@JsonProperty("field") String field) {
            this.field = field;
        }
    }

    static class TestEventSaver extends EventSaver<TestEvent> {
        @Override
        public void save(TestEvent evenement) {
            eventQueue.add(evenement);
        }
    }
}
