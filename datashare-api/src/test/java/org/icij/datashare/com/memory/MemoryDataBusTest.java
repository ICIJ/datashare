package org.icij.datashare.com.memory;

import org.icij.datashare.com.Message;
import org.icij.datashare.com.ShutdownMessage;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.com.Channel.NLP;
import static org.icij.datashare.com.Channel.TEST;

public class MemoryDataBusTest {
    private ExecutorService executor = Executors.newFixedThreadPool(2);
    private MemoryDataBus dataBus = new MemoryDataBus();

    @Test(expected = NullPointerException.class)
    public void test_null_message() {
        dataBus.publish(TEST, null);
    }

    @Test(timeout = 5000)
    public void test_subscribe_unsubscribe() throws Exception {
        AtomicInteger received = new AtomicInteger();
        Consumer<Message> messageConsumer = message -> received.getAndIncrement();
        CountDownLatch subscription = new CountDownLatch(1);
        Future<Integer> subscribed = executor.submit(() -> dataBus.subscribe(messageConsumer, subscription::countDown, TEST));
        subscription.await(1, TimeUnit.SECONDS);

        dataBus.unsubscribe(messageConsumer);
        dataBus.publish(TEST, new Message(Message.Type.EXTRACT_NLP));

        assertThat(received.get()).isEqualTo(1); // shutdown message
        assertThat(subscribed.get()).isEqualTo(1);
    }

    @Test(timeout = 5000)
    public void test_pub_sub_one_subscriber() throws InterruptedException {
        AtomicInteger received = new AtomicInteger();
         Consumer<Message> messageConsumer = message -> received.getAndIncrement();
         CountDownLatch subscription = new CountDownLatch(1);
         executor.submit(() -> dataBus.subscribe(messageConsumer, subscription::countDown, TEST));
         subscription.await(1, TimeUnit.SECONDS);

         dataBus.publish(TEST, new Message(Message.Type.EXTRACT_NLP));
         dataBus.unsubscribe(messageConsumer);

         assertThat(received.get()).isEqualTo(2); // +shutdown
    }

    @Test(timeout = 5000)
    public void test_pub_sub_one_subscriber_other_channel() throws InterruptedException {
        AtomicInteger received = new AtomicInteger();
        Consumer<Message> messageConsumer = message -> received.getAndIncrement();
        CountDownLatch subscription = new CountDownLatch(1);
        executor.submit(() -> dataBus.subscribe(messageConsumer, subscription::countDown, TEST));
        subscription.await(1, TimeUnit.SECONDS);

        dataBus.publish(NLP, new Message(Message.Type.EXTRACT_NLP));
        dataBus.unsubscribe(messageConsumer);

        assertThat(received.get()).isEqualTo(1); //shutdown
    }

    @Test(timeout = 5000)
    public void test_pub_sub_two_subscribers() throws InterruptedException {
        AtomicInteger received = new AtomicInteger();
        CountDownLatch subscriptions = new CountDownLatch(2);

        executor.submit(() -> dataBus.subscribe(message -> received.getAndIncrement(), subscriptions::countDown, TEST));
        executor.submit(() -> dataBus.subscribe(message -> received.getAndIncrement(), subscriptions::countDown, TEST));
        subscriptions.await(1, TimeUnit.SECONDS);

        dataBus.publish(TEST, new Message(Message.Type.EXTRACT_NLP));
        dataBus.publish(TEST, new ShutdownMessage());

        assertThat(received.get()).isEqualTo(4); // EXTRACT received by 2 subscribers + 2 shutdown messages
    }

    @After
    public void tearDown() throws Exception {
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }
}
