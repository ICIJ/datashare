package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;
import redis.clients.util.JedisURIHelper;

import java.net.URI;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_REDIS_ADDRESS;
import static org.icij.datashare.cli.DatashareCliOptions.REDIS_ADDRESS_OPT;

final class RedisPoolFactory {
    private static final long EVICTION_RUN_INTERVAL_MILLIS = 30_000;
    private static final String SSL_SCHEME = "rediss";

    private RedisPoolFactory() {}

    static JedisPool createPool(PropertiesProvider propertiesProvider) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        // Validate connections before handing them out: a connection killed server-side while idle
        // in the pool still looks alive locally (socket flags don't reflect a remote close), so
        // without this the next command on it fails with "Unexpected end of stream".
        poolConfig.setTestOnBorrow(true);
        // Also proactively reap dead idle connections in the background, so a killed connection
        // doesn't silently sit in the pool (reducing effective capacity) until something borrows it.
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRunsMillis(EVICTION_RUN_INTERVAL_MILLIS);
        String redisAddress = propertiesProvider.get(REDIS_ADDRESS_OPT).orElse(DEFAULT_REDIS_ADDRESS);
        URI uri = URI.create(redisAddress);
        // Take the host and port apart rather than passing the URI. JedisPool's URI constructors
        // hand JedisFactory a hardcoded ssl=false and never look at the scheme, so a rediss://
        // address would connect in plaintext and every command would time out against a TLS-only
        // server. JedisPool(String) reads the scheme, but it accepts no pool config, and the
        // settings above are the point of this factory.
        boolean ssl = SSL_SCHEME.equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() == -1 ? Protocol.DEFAULT_PORT : uri.getPort();
        return new JedisPool(poolConfig, uri.getHost(), port, Protocol.DEFAULT_TIMEOUT,
                JedisURIHelper.getPassword(uri), JedisURIHelper.getDBIndex(uri), null, ssl);
    }
}
