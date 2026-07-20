package org.icij.datashare.session;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.lang.reflect.Field;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class UsersInRedisTest {
    UsersInRedis users = new UsersInRedis(new PropertiesProvider(new HashMap<>() {{
        put("messageBusAddress", EnvUtils.resolveUri("redis", "redis://redis:6379"));
    }}));

    @Test
    public void find_recovers_from_a_connection_killed_server_side_while_idle_in_the_pool() throws Exception {
        // Simulates the real-world failure: the Redis server (or an intermediary like a LB/NAT)
        // closes a connection that's sitting idle in the pool. Locally, the socket's isConnected()/
        // isClosed() flags are untouched by a remote close (they only reflect local connect()/close()
        // calls), so Jedis believes the connection is fine and hands it out again; the next command
        // fails reading the reply with "Unexpected end of stream", exactly like the reported crash.
        users.save(new org.icij.datashare.user.User("test"));

        Field poolField = UsersInRedis.class.getDeclaredField("redis");
        poolField.setAccessible(true);
        JedisPool pool = (JedisPool) poolField.get(users);
        String redisAddress = EnvUtils.resolveUri("redis", "redis://redis:6379");
        try (Jedis jedis = pool.getResource(); JedisPool adminPool = new JedisPool(redisAddress); Jedis admin = adminPool.getResource()) {
            String connectionName = "kill-target-test-users-in-redis";
            jedis.clientSetname(connectionName.getBytes());
            String clientAddr = RedisTestUtils.addrForConnectionName(admin.clientList(), connectionName);
            admin.clientKill(clientAddr.getBytes());
        }

        assertThat(users.find("test")).isNotNull();
    }
}
