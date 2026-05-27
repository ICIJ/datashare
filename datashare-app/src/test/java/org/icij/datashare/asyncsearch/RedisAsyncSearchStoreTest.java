package org.icij.datashare.asyncsearch;

import org.icij.datashare.EnvUtils;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.junit.After;
import org.junit.Test;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class RedisAsyncSearchStoreTest {
    private static final String REDIS_ADDRESS = EnvUtils.resolveUri("redis", "redis://redis:6379");
    private final RedissonClient client = new RedissonClientFactory()
            .withOptions(Options.from(Map.of("redisAddress", REDIS_ADDRESS, "redisPoolSize", "5"))).create();
    private final RedisAsyncSearchStore store = new RedisAsyncSearchStore(client);
    private final AsyncSearchOwner owner = new AsyncSearchOwner("alice", List.of("alice-datashare"));

    @Test
    public void test_put_then_get_returns_owner() {
        store.put("id-redis-1", owner, Duration.ofMinutes(5));
        assertThat(store.get("id-redis-1").isPresent()).isTrue();
        assertThat(store.get("id-redis-1").get().userId).isEqualTo("alice");
        assertThat(store.get("id-redis-1").get().projects).containsExactly("alice-datashare");
    }

    @Test
    public void test_get_unknown_id_is_empty() {
        assertThat(store.get("id-redis-unknown").isPresent()).isFalse();
    }

    @Test
    public void test_remove() {
        store.put("id-redis-2", owner, Duration.ofMinutes(5));
        store.remove("id-redis-2");
        assertThat(store.get("id-redis-2").isPresent()).isFalse();
    }

    @Test
    public void test_entry_expires_after_keep_alive() throws InterruptedException {
        store.put("id-redis-3", owner, Duration.ofMillis(200));
        assertThat(store.get("id-redis-3").isPresent()).isTrue();
        Thread.sleep(400);
        assertThat(store.get("id-redis-3").isPresent()).isFalse();
    }

    @Test
    public void test_refresh_extends_expiry() throws InterruptedException {
        store.put("id-redis-4", owner, Duration.ofMillis(300));
        store.refresh("id-redis-4", Duration.ofSeconds(10)); // extend well past the original 300ms
        Thread.sleep(500); // past the original TTL
        assertThat(store.get("id-redis-4").isPresent()).isTrue();
    }

    @After
    public void tearDown() {
        store.remove("id-redis-1");
        store.remove("id-redis-2");
        store.remove("id-redis-3");
        store.remove("id-redis-4");
        client.shutdown();
    }
}
