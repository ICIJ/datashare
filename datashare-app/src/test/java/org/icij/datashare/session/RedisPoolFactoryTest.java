package org.icij.datashare.session;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;

import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class RedisPoolFactoryTest {
    static JedisPooled pool;

    @BeforeClass
    public static void createPool() {
        pool = RedisPoolFactory.createPool(new PropertiesProvider(new HashMap<>() {{
            put("redisAddress", EnvUtils.resolveUri("redis", "redis://redis:6379"));
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
        // CLIENT SETNAME/LIST/KILL are per-connection commands with no equivalent on the pooled
        // JedisPooled command surface, so we borrow the exact connection the pool would hand out
        // (via its underlying Pool<Connection>) and wrap it in a plain Jedis just to name/kill it.
        try (Jedis jedis = new Jedis(pool.getPool().getResource()); Jedis admin = new Jedis(redisAddress)) {
            String connectionName = "kill-target-test-redis-pool-factory";
            jedis.clientSetname(connectionName.getBytes());
            String clientAddr = addrForConnectionName(admin.clientList(), connectionName);
            admin.clientKill(clientAddr.getBytes());
        }

        assertThat(pool.set("recovery-probe", "ok")).isEqualTo("OK");
        pool.del("recovery-probe");
    }

    private static String addrForConnectionName(String clientList, String connectionName) {
        for (String line : clientList.split("\n")) {
            if (line.contains("name=" + connectionName + " ")) {
                for (String field : line.split(" ")) {
                    if (field.startsWith("addr=")) {
                        return field.substring("addr=".length());
                    }
                }
            }
        }
        throw new IllegalStateException("no client found with name " + connectionName + " in:\n" + clientList);
    }
}
