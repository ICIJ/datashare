package org.icij.datashare.extract;

import org.icij.datashare.EnvUtils;
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
    Map<String, Object> redisConfig = Map.of("redisAddress", "redis://" + EnvUtils.resolveHost("redis") + ":6379", "redisPoolSize", "5");
    RedissonClient client = new RedissonClientFactory().withOptions(Options.from(redisConfig)).create();
    RedisBlockingQueue<String> queue = new RedisBlockingQueue<>(client, "ds:tasks:queue:test");

    @Test
    public void test_redisson_connection(){
        RedissonClient redissonClient = new RedissonClientFactory().withOptions(Options.from(new HashMap<>() {{
            put("redisAddress", "redis://" + EnvUtils.resolveHost("redis") + ":6379");
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
