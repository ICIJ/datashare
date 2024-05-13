package org.icij.datashare.extract;

import org.icij.datashare.PropertiesProvider;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Test;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;

public class RedisBlockingQueueTest {
    RedisBlockingQueue<String> queue = new RedisBlockingQueue<>(new PropertiesProvider(Map.of(
            "redisAddress", "redis://redis:6379",
            "redisPoolSize", "5"
    )), "test:queue");

    @Test
    public void test_redisson_connection(){
        RedissonClient redissonClient = new RedissonClientFactory().withOptions(Options.from(new HashMap<>() {{
            put("redisAddress", "redis://redis:6379");
        }})).create();
        redissonClient.shutdown();
    }
    @Test
    public void test_offer_poll() {
        assertThat(queue.offer("test")).isTrue();
        assertThat(queue.poll()).isEqualTo("test");
    }

    @Test
    public void test_offer_poll_with_timeout() throws InterruptedException {
        assertThat(queue.poll(10, TimeUnit.MILLISECONDS)).isNull();
    }

    @After
    public void tearDown() throws Exception {
        queue.delete();
        queue.close();
    }
}
