package org.icij.datashare.com.redis;

import org.icij.datashare.com.Message;
import org.icij.datashare.com.ShutdownMessage;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.com.Channel.NLP;
import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;

public class JedisPubsubTest {
    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Test
    public void test_publish_subscribe() throws Exception {
        AtomicReference<Message> receivedMessage = new AtomicReference<>();
        executorService.submit(new RedisSubscriber(createJedis(), message -> {
            receivedMessage.set(message);
            return null;
        }).subscribe(NLP));

        RedisPublisher publisher = new RedisPublisher(createJedis());
        Message doc_id = new Message(EXTRACT_NLP).add(DOC_ID, "doc_id");
        publisher.publish(NLP, doc_id);
        publisher.publish(NLP, new ShutdownMessage());

        executorService.awaitTermination(5, SECONDS);
        assertThat(receivedMessage.get()).isEqualTo(doc_id);
    }

    private Jedis createJedis() {
        return new Jedis("redis");
    }
}
