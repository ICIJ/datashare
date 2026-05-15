package org.icij.datashare.policies;

import org.casbin.jcasbin.persist.Watcher;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.io.Closeable;
import java.util.function.Consumer;

public class PolicyWatcher implements Watcher, Closeable {
    private static final String TOPIC = "datashare:policy-updated";
    private final RTopic topic;
    private int listenerId = -1;

    public PolicyWatcher(RedissonClient redisson) {
        this.topic = redisson.getTopic(TOPIC);
    }

    @Override
    public void setUpdateCallback(Runnable callback) {
        listenerId = topic.addListener(String.class, (ch, msg) -> callback.run());
    }

    @Override
    public void setUpdateCallback(Consumer<String> callback) {
        listenerId = topic.addListener(String.class, (ch, msg) -> callback.accept(msg));
    }

    @Override
    public void update() {
        topic.publish("reload");
    }

    @Override
    public void close() {
        if (listenerId != -1) topic.removeListener(listenerId);
    }
}
