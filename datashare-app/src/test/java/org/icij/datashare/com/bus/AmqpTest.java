package org.icij.datashare.com.bus;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import org.icij.datashare.json.JsonObjectMapper;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.fest.assertions.Assertions.assertThat;

public class AmqpTest {
    static BlockingQueue<TestEvent> eventQueue = new ArrayBlockingQueue<>(10);
    @ClassRule
    static public AmqpServerRule qpid = new AmqpServerRule(12345);
    ExecutorService executor = Executors.newSingleThreadExecutor();

    @Test
    public void test_publish_receive() throws Exception {
        AmqpInterlocutor amqpInterlocutor = AmqpInterlocutor.initWith(new Configuration("localhost", 12345, "admin", "admin", 10));
        amqpInterlocutor.createAmqpChannel(AmqpQueue.EVENT);
        TestConsumer consumer = new TestConsumer(new TestEventSaver(), AmqpQueue.EVENT);
        executor.execute(consumer::consumeEvents);

        amqpInterlocutor.publish(AmqpQueue.EVENT, new TestEvent("hello AMQP"));

        assertThat(eventQueue.take().field).isEqualTo("hello AMQP");
        consumer.shutdown();
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
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
