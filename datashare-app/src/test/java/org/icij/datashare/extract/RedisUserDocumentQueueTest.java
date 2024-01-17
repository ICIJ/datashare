package org.icij.datashare.extract;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.nio.file.Path;
import java.util.HashMap;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.User.nullUser;

public class RedisUserDocumentQueueTest {
    private Jedis redis = new Jedis("redis");

    @After
    public void tearDown() {
        redis.del("extract:queue", "extract:queue_foo");
    }

    @Test
    public void test_redis_queue_name_with_null_user() {
        RedisUserDocumentQueue<Path> queue = new RedisUserDocumentQueue<>(nullUser(), new PropertiesProvider(new HashMap<>() {{
            put("redisAddress", "redis://redis:6379");
        }}), Path.class);
        queue.offer(get("/path/to/doc"));

        assertThat(redis.keys("extract:queue")).hasSize(1);
        assertThat(redis.lpop("extract:queue")).isEqualTo("/path/to/doc");
    }
    @Test
    public void test_redis_queue_name_with_user_not_null_and_no_parameter_queue_name() {
        RedisUserDocumentQueue<Path> queue = new RedisUserDocumentQueue<>(new User("foo"), new PropertiesProvider(new HashMap<>() {{
            put("redisAddress", "redis://redis:6379");
        }}), Path.class);
        queue.offer(get("/path/to/doc"));

        assertThat(redis.keys("extract:queue_foo")).hasSize(1);
        assertThat(redis.lpop("extract:queue_foo")).isEqualTo("/path/to/doc");
    }

    @Test
    public void test_redis_queue_name_with_user_not_null_and_queue_name__user_queue_is_preferred() {
        RedisUserDocumentQueue<Path> queue = new RedisUserDocumentQueue<>(new User("foo"),
                new PropertiesProvider(new HashMap<>() {{
                    put("redisAddress", "redis://redis:6379");
                    put("queueName", "myqueue");
                }}), Path.class);
        queue.offer(get("/path/to/doc"));

        assertThat(redis.keys("extract:queue_foo")).hasSize(1);
        assertThat(redis.lpop("extract:queue_foo")).isEqualTo("/path/to/doc");
    }
}
