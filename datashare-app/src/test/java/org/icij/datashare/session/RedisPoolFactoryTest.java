package org.icij.datashare.session;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class RedisPoolFactoryTest {
    static JedisPool pool;

    @BeforeClass
    public static void createPool() {
        pool = RedisPoolFactory.createPool(new PropertiesProvider(new HashMap<>() {{
            put("messageBusAddress", EnvUtils.resolveUri("redis", "redis://redis:6379"));
        }}));
    }

    @AfterClass
    public static void closePool() {
        pool.close();
    }

    @Test
    public void pool_recovers_from_a_connection_killed_server_side_while_idle_in_the_pool() throws Exception {
        // Simulates the real-world failure: the Redis server (or an intermediary like a LB/NAT)
        // closes a connection that's sitting idle in the pool. Locally, the socket's isConnected()/
        // isClosed() flags are untouched by a remote close (they only reflect local connect()/close()
        // calls), so Jedis believes the connection is fine and hands it out again; the next command
        // fails reading the reply with "Unexpected end of stream", exactly like the reported crash.
        // testOnBorrow (set by RedisPoolFactory) is what prevents that: the pool validates a
        // connection before handing it out and transparently replaces it if it's dead.
        String redisAddress = EnvUtils.resolveUri("redis", "redis://redis:6379");
        try (Jedis jedis = pool.getResource(); JedisPool adminPool = new JedisPool(redisAddress); Jedis admin = adminPool.getResource()) {
            String connectionName = "kill-target-test-redis-pool-factory";
            jedis.clientSetname(connectionName.getBytes());
            String clientAddr = RedisTestUtils.addrForConnectionName(admin.clientList(), connectionName);
            admin.clientKill(clientAddr.getBytes());
        }

        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.set("recovery-probe", "ok")).isEqualTo("OK");
            jedis.del("recovery-probe");
        }
    }
}
