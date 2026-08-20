package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.InvalidURIException;
import redis.clients.jedis.exceptions.JedisException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_REDIS_ADDRESS;
import static org.icij.datashare.cli.DatashareCliOptions.REDIS_ADDRESS_OPT;
import static org.junit.Assert.fail;

/**
 * Covers the single invariant of the factory: an address every Redis client in the app agrees on
 * reaches Jedis with its scheme honoured on the wire, and any other address is refused by name
 * before a socket is opened.
 */
public class RedisPoolFactoryTest {
    private static final byte TLS_HANDSHAKE_RECORD = 0x16;
    private static final byte RESP_ARRAY = '*';
    private static final int PROBE_TIMEOUT_MS = 10_000;

    static JedisPool pool;

    @BeforeClass
    public static void createPool() {
        pool = poolFor(DEFAULT_REDIS_ADDRESS);
    }

    @AfterClass
    public static void closePool() {
        // JUnit runs the afters even when @BeforeClass threw, and an NPE here would headline the
        // report instead of the address that actually failed.
        if (pool != null) {
            pool.close();
        }
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
        try (Jedis jedis = pool.getResource(); JedisPool adminPool = new JedisPool(DEFAULT_REDIS_ADDRESS); Jedis admin = adminPool.getResource()) {
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

    @Test(timeout = PROBE_TIMEOUT_MS)
    public void a_rediss_address_starts_a_tls_handshake() throws Exception {
        // A ClientHello is the proof the scheme was honoured. Asserting on the resulting failure
        // instead passes whenever Redis is merely unreachable, and costs the read timeout.
        assertThat(firstByteSentTo("rediss")).isEqualTo(TLS_HANDSHAKE_RECORD);
    }

    @Test(timeout = PROBE_TIMEOUT_MS)
    public void a_redis_address_speaks_plaintext_resp() throws Exception {
        assertThat(firstByteSentTo("redis")).isEqualTo(RESP_ARRAY);
    }

    @Test
    public void the_database_index_comes_from_the_address_path() {
        // Only this pool honours the path: Redisson, handed the same option, drops it and stays on
        // database 0, so an address carrying one splits the app across two databases.
        try (JedisPool databasePool = poolFor(configuredAddressWithDatabase(2));
             Jedis jedis = databasePool.getResource()) {
            assertThat(jedis.getDB()).isEqualTo(2);
        }
    }

    @Test(expected = InvalidURIException.class)
    public void an_address_missing_its_scheme_separator_is_rejected_up_front() {
        poolFor("redis:6379");
    }

    @Test(expected = InvalidURIException.class)
    public void an_address_without_a_scheme_is_rejected_up_front() {
        poolFor("//redis:6379");
    }

    @Test(expected = InvalidURIException.class)
    public void an_address_without_a_port_is_rejected_up_front() {
        // Redisson, fed the same option, dies on a port-less address with a raw
        // StringIndexOutOfBoundsException, so accepting one here would let the app boot halfway.
        poolFor("redis://redis");
    }

    @Test(expected = InvalidURIException.class)
    public void a_scheme_that_is_neither_redis_nor_rediss_is_rejected_up_front() {
        poolFor("redis+tls://redis:6379");
    }

    @Test(expected = InvalidURIException.class)
    public void an_uppercase_scheme_is_rejected_up_front() {
        poolFor("REDISS://redis:6379");
    }

    @Test(expected = InvalidURIException.class)
    public void an_unparseable_address_is_rejected_up_front() {
        poolFor("redis://redis:6379 ");
    }

    @Test(expected = InvalidURIException.class)
    public void a_user_info_without_a_password_is_rejected_up_front() {
        poolFor("redis://user@redis:6379");
    }

    @Test(expected = InvalidURIException.class)
    public void a_non_numeric_database_index_is_rejected_up_front() {
        poolFor("redis://redis:6379/nope");
    }

    @Test
    public void the_rejection_message_keeps_the_password_out_of_the_logs() {
        try {
            poolFor("redis://:s3cr3t@redis_1:6379");
            fail("an address whose host cannot be parsed must be rejected");
        } catch (InvalidURIException e) {
            assertThat(e.getMessage()).excludes("s3cr3t");
            assertThat(e.getMessage()).contains("***");
        }
    }

    private static byte firstByteSentTo(String scheme) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread borrower = borrowerAgainst(scheme + "://127.0.0.1:" + server.getLocalPort());
            borrower.start();
            byte first = firstByteAccepted(server);
            borrower.join(PROBE_TIMEOUT_MS);
            return first;
        }
    }

    private static byte firstByteAccepted(ServerSocket server) throws IOException {
        try (Socket accepted = server.accept()) {
            return (byte) accepted.getInputStream().read();
        }
    }

    /**
     * Borrows once from a pool pointed at a server that reads a single byte and hangs up, so the
     * borrow always fails: what is asserted is the byte that reached the wire, not the connection.
     */
    private static Thread borrowerAgainst(String redisAddress) {
        return new Thread(() -> {
            try (JedisPool probePool = poolFor(redisAddress); Jedis jedis = probePool.getResource()) {
                jedis.ping();
            } catch (JedisException expected) {
            }
        });
    }

    /** Rebuilt from the configured authority rather than concatenated, which a trailing slash or an
     *  already present database index in the devenv address would turn into a NumberFormatException. */
    private static String configuredAddressWithDatabase(int database) {
        URI configured = URI.create(DEFAULT_REDIS_ADDRESS);
        return "%s://%s/%d".formatted(configured.getScheme(), configured.getAuthority(), database);
    }

    private static JedisPool poolFor(String redisAddress) {
        return RedisPoolFactory.createPool(new PropertiesProvider(Map.<String, Object>of(REDIS_ADDRESS_OPT, redisAddress)));
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
