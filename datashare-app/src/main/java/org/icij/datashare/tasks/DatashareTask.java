package org.icij.datashare.tasks;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.user.User;

public class DatashareTask {
    public static final String USER_KEY = "user";

    public static <V> Task<V> task(String name, User user, Map<String, Object> args) {
        return new Task<>(name, addTo(args, user));
    }

    public static <V> Task<V> task(String id, String name, User user) {
        return new Task<>(id, name, addTo(new HashMap<>(), user));
    }

    public static <V> Task<V> task(String id, String name, User user, Map<String, Object> args) {
        return new Task<>(id, name, addTo(args, user));
    }

    public static User getUser(Task<?> task) {
        return (User) task.args.get(USER_KEY);
    }

    private static Map<String, Object> addTo(Map<String, Object> properties, User user) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(properties);
        result.put(USER_KEY, user);
        return result;
    }
}
