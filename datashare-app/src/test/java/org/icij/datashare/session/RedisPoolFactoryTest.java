package org.icij.datashare.session;

import org.icij.datashare.EnvUtils;
import org.icij.datashare.PropertiesProvider;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.InvalidURIException;
import redis.clients.jedis.exceptions.JedisException;

import java.net.URI;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;

public class RedisPoolFactoryTest {
    static final String REDIS_ADDRESS = EnvUtils.resolveUri("redis", "redis://redis:6379");
    static final String REDIS_HOST = URI.create(REDIS_ADDRESS).getHost();
    static JedisPool pool;

    @BeforeClass
    public static void createPool() {
        pool = poolFor(REDIS_ADDRESS);
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
        try (Jedis jedis = pool.getResource(); JedisPool adminPool = new JedisPool(REDIS_ADDRESS); Jedis admin = adminPool.getResource()) {
            String connectionName = "kill-target-test-redis-pool-factory";
            jedis.clientSetname(connectionName.getBytes());
            String clientAddr = addrForConnectionName(admin.clientList(), connectionName);
            admin.clientKill(clientAddr.getBytes());
        }

        try (Jedis jedis = pool.getResource()) {
            assertThat(jedis.set("recovery-probe", "ok")).isEqualTo("OK");
            jedis.del("recovery-probe");
        }
    }

    @Test(expected = JedisException.class)
    public void a_rediss_address_negotiates_tls_so_a_plaintext_server_rejects_it() {
        // Asserting the failure is how the ssl flag becomes observable: Jedis keeps it private
        // behind the pool's factory, so the handshake against a plaintext server is the only proof
        // the scheme was honoured. If ssl were ignored this connects happily and the test fails.
        try (JedisPool tlsPool = poolFor(REDIS_ADDRESS.replaceFirst("^redis://", "rediss://"));
             Jedis jedis = tlsPool.getResource()) {
            jedis.ping();
        }
    }

    @Test
    public void an_address_without_a_port_falls_back_to_the_default_port() {
        try (JedisPool pool = poolFor("redis://" + REDIS_HOST); Jedis jedis = pool.getResource()) {
            assertThat(jedis.ping()).isEqualTo("PONG");
        }
    }

    @Test
    public void the_database_index_comes_from_the_address_path() {
        try (JedisPool pool = poolFor(REDIS_ADDRESS + "/2"); Jedis jedis = pool.getResource()) {
            assertThat(jedis.getDB()).isEqualTo(2L);
        }
    }

    @Test(expected = InvalidURIException.class)
    public void an_address_missing_its_scheme_separator_is_rejected_up_front() {
        poolFor("redis:6379");
    }

    private static JedisPool poolFor(String redisAddress) {
        return RedisPoolFactory.createPool(new PropertiesProvider(new HashMap<>() {{
            put("redisAddress", redisAddress);
        }}));
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
