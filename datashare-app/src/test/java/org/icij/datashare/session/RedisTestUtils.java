package org.icij.datashare.session;

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
}
