package org.icij.datashare.asyncsearch;

import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed ownership store for server modes (REDIS/AMQP/TEMPORAL).
 * Uses an RMapCache so each entry expires independently after keepAlive, and
 * the record is shared across all backend instances.
 */
public class RedisAsyncSearchStore implements AsyncSearchStore {
    private static final String MAP_NAME = "datashare:async-search";
    private final RMapCache<String, AsyncSearchOwner> map;

    public RedisAsyncSearchStore(RedissonClient redissonClient) {
        java.util.Objects.requireNonNull(redissonClient, "redissonClient must not be null");
        this.map = redissonClient.getMapCache(MAP_NAME, new JsonJacksonCodec());
    }

    @Override
    public void put(String asyncId, AsyncSearchOwner owner, Duration keepAlive) {
        map.put(asyncId, owner, keepAlive.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<AsyncSearchOwner> get(String asyncId) {
        return Optional.ofNullable(map.get(asyncId));
    }

    @Override
    public void remove(String asyncId) {
        map.remove(asyncId);
    }

    @Override
    public void refresh(String asyncId, Duration keepAlive) {
        map.updateEntryExpiration(asyncId, keepAlive.toMillis(), TimeUnit.MILLISECONDS, 0, TimeUnit.MILLISECONDS);
    }
}
