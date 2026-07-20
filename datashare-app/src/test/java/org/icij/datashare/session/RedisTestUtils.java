package org.icij.datashare.session;

import redis.clients.jedis.JedisPool;

import java.lang.reflect.Field;

final class RedisTestUtils {
    private RedisTestUtils() {}

    static String addrForConnectionName(String clientList, String connectionName) {
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

    static void closeRedisPool(Object redisBackedInstance) throws Exception {
        Field poolField = redisBackedInstance.getClass().getDeclaredField("redis");
        poolField.setAccessible(true);
        ((JedisPool) poolField.get(redisBackedInstance)).close();
    }
}
