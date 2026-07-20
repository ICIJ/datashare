package org.icij.datashare.session;

import redis.clients.jedis.JedisPool;

import java.lang.reflect.Field;

final class RedisTestUtils {
    private RedisTestUtils() {}

    static void closeRedisPool(Object redisBackedInstance) throws Exception {
        Field poolField = redisBackedInstance.getClass().getDeclaredField("redis");
        poolField.setAccessible(true);
        ((JedisPool) poolField.get(redisBackedInstance)).close();
    }
}
