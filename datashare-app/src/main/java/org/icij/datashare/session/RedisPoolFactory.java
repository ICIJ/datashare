package org.icij.datashare.session;

import org.icij.datashare.PropertiesProvider;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.JedisPooled;

import java.net.URI;

import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_REDIS_ADDRESS;
import static org.icij.datashare.cli.DatashareCliOptions.REDIS_ADDRESS_OPT;

final class RedisPoolFactory {
    private static final long EVICTION_RUN_INTERVAL_MILLIS = 30_000;

    private RedisPoolFactory() {}

    static JedisPooled createPool(PropertiesProvider propertiesProvider) {
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        // Validate connections before handing them out: a connection killed server-side while idle
        // in the pool still looks alive locally (socket flags don't reflect a remote close), so
        // without this the next command on it fails with "Unexpected end of stream".
        poolConfig.setTestOnBorrow(true);
        // Also proactively reap dead idle connections in the background, so a killed connection
        // doesn't silently sit in the pool (reducing effective capacity) until something borrows it.
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRunsMillis(EVICTION_RUN_INTERVAL_MILLIS);
        String redisAddress = propertiesProvider.get(REDIS_ADDRESS_OPT).orElse(DEFAULT_REDIS_ADDRESS);
        return new JedisPooled(poolConfig, URI.create(redisAddress));
    }
}
