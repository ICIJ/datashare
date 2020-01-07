package org.icij.datashare.com.memory;

import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.com.Channel.NLP;
import static org.icij.datashare.com.Channel.TEST;

public class MemoryDataBusTest {
    private MemoryDataBus dataBus = new MemoryDataBus();

    @Test
    public void test_subscribe_unsubscribe() {
        AtomicInteger received = new AtomicInteger();
        Consumer<Message> messageConsumer = message -> received.getAndIncrement();
        dataBus.subscribe(messageConsumer, TEST);
        dataBus.unsubscribe(messageConsumer);

        dataBus.publish(TEST, new Message(Message.Type.EXTRACT_NLP));

        assertThat(received.get()).isEqualTo(0);
    }

    @Test
    public void test_subscribe_with_callback() {
        AtomicInteger subscribed = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();

        dataBus.subscribe(message -> received.getAndIncrement(), subscribed::getAndIncrement, Channel.TEST);

        assertThat(subscribed.get()).isEqualTo(1);
    }

    @Test
    public void test_pub_sub_one_subscriber() {
        AtomicInteger received = new AtomicInteger();
        dataBus.subscribe(message -> received.getAndIncrement(), TEST);

        dataBus.publish(TEST, new Message(Message.Type.EXTRACT_NLP));

        assertThat(received.get()).isEqualTo(1);
    }

    @Test
    public void test_pub_sub_one_subscriber_other_channel() {
        AtomicInteger received = new AtomicInteger();
        dataBus.subscribe(message -> received.getAndIncrement(), TEST);

        dataBus.publish(NLP, new Message(Message.Type.EXTRACT_NLP));

        assertThat(received.get()).isEqualTo(0);
    }

    @Test
    public void test_pub_sub_two_subscribers() {
        AtomicInteger received = new AtomicInteger();
        dataBus.subscribe(message -> received.getAndIncrement(), TEST);
        dataBus.subscribe(message -> received.getAndIncrement(), TEST);

        dataBus.publish(TEST, new Message(Message.Type.EXTRACT_NLP));

        assertThat(received.get()).isEqualTo(2);
    }
}
