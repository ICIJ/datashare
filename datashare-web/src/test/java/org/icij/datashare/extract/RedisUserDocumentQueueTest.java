package org.icij.datashare.extract;

import org.icij.datashare.user.User;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.junit.After;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class RedisUserDocumentQueueTest {
    private Jedis redis = new Jedis("redis");

    @After
    public void tearDown() {
        redis.del("extract:queue", "extract:queue_foo");
    }

    @Test
    public void test_redis_queue_name_with_user_null() {
        RedisUserDocumentQueue queue = new RedisUserDocumentQueue(null, new OptionsWrapper(new HashMap<String, String>() {{ put("redisAddress", "redis://redis:6379");}}).asOptions());
        queue.offer(new DocumentFactory().withIdentifier(new PathIdentifier()).create("/path/to/doc"));

        assertThat(redis.keys("extract:queue")).hasSize(1);
        assertThat(redis.lpop("extract:queue")).isEqualTo("/path/to/doc");
    }
    @Test
    public void test_redis_queue_name_with_user_not_null_and_no_parameter_queue_name() {
        RedisUserDocumentQueue queue = new RedisUserDocumentQueue(new User("foo"), new OptionsWrapper(new HashMap<String, String>() {{ put("redisAddress", "redis://redis:6379");}}).asOptions());
        queue.offer(new DocumentFactory().withIdentifier(new PathIdentifier()).create("/path/to/doc"));

        assertThat(redis.keys("extract:queue_foo")).hasSize(1);
        assertThat(redis.lpop("extract:queue_foo")).isEqualTo("/path/to/doc");
    }

    @Test
    public void test_redis_queue_name_with_user_not_null_and_queue_name() {
        RedisUserDocumentQueue queue = new RedisUserDocumentQueue(new User("foo"),
                new OptionsWrapper(new HashMap<String, String>() {{
                    put("redisAddress", "redis://redis:6379");
                    put("queueName", "myqueue");}}).asOptions());
        queue.offer(new DocumentFactory().withIdentifier(new PathIdentifier()).create("/path/to/doc"));

        assertThat(redis.keys("myqueue_foo")).hasSize(1);
        assertThat(redis.lpop("myqueue_foo")).isEqualTo("/path/to/doc");
    }
}