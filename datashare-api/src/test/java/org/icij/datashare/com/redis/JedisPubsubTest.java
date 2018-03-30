package org.icij.datashare.com.redis;

import org.icij.datashare.com.Message;
import org.icij.datashare.com.ShutdownMessage;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Collections.synchronizedList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.com.Channel.NLP;
import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;

public class JedisPubsubTest {
    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Test
    public void test_publish_subscribe() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<Message> msgList = synchronizedList(new ArrayList<Message>());
        executorService.submit(new RedisSubscriber(createJedis(), msgList::add, latch::countDown).subscribe(NLP));
        latch.await(2, SECONDS);

        RedisPublisher publisher = new RedisPublisher(createJedis());
        Message doc_id = new Message(EXTRACT_NLP).add(DOC_ID, "doc_id");
        publisher.publish(NLP, doc_id);
        publisher.publish(NLP, new ShutdownMessage());

        executorService.shutdown();
        executorService.awaitTermination(1, SECONDS);
        assertThat(msgList.get(0)).isEqualTo(doc_id);
    }

    private Jedis createJedis() {
        return new Jedis("redis");
    }
}
