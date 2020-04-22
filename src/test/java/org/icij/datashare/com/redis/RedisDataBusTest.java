package org.icij.datashare.com.redis;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.ShutdownMessage;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static java.util.Collections.synchronizedList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;

public class RedisDataBusTest {
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    CountDownLatch latch = new CountDownLatch(1);
    RedisDataBus dataBus = new RedisDataBus(new PropertiesProvider());

    @Test
    public void test_subscribe_unsubscribe() throws InterruptedException {
        Consumer<Message> callback = System.out::println;
        executorService.submit(() -> dataBus.subscribe(callback, latch::countDown, Channel.TEST));
        latch.await(2, SECONDS);

        dataBus.unsubscribe(callback);

        executorService.shutdown();
        // if unsubscribe is not called the thread will not end properly and the test will last more than 1s
        assertThat(executorService.awaitTermination(1, SECONDS)).isTrue();
    }

    @Test
    public void test_publish_subscribe() throws InterruptedException {
        List<Message> msgList = synchronizedList(new ArrayList<>());
        executorService.submit(() -> dataBus.subscribe(msgList::add, latch::countDown, Channel.TEST));
        latch.await(2, SECONDS);

        Message doc_id = new Message(Message.Type.EXTRACT_NLP).add(Message.Field.DOC_ID, "doc_id");
        dataBus.publish(Channel.TEST, doc_id);
        dataBus.publish(Channel.TEST, new ShutdownMessage());

        executorService.shutdown();
        executorService.awaitTermination(1, SECONDS);

        assertThat(msgList.size()).isEqualTo(2);
        assertThat(msgList.get(0)).isEqualTo(doc_id);
    }

    @After
    public void tearDown() {
        dataBus.close();
    }
}
