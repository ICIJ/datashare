package org.icij.datashare.policies;

import org.casbin.jcasbin.persist.Watcher;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.io.Closeable;
import java.util.UUID;
import java.util.function.Consumer;

public class PolicyWatcher implements Watcher, Closeable {
    private static final String TOPIC = "datashare:policy-updated";
    private final RTopic topic;
    // Unique per-instance ID so each node ignores its own publications.
    // Redisson delivers messages to all subscribers on the topic including the
    // publisher itself; without this guard, addGroupingPolicy() would trigger
    // an unnecessary loadPolicy() reload on the very instance that just wrote
    // the change, momentarily clearing the in-memory model.
    private final String instanceId = UUID.randomUUID().toString();
    private int listenerId = -1;

    public PolicyWatcher(RedissonClient redisson) {
        this.topic = redisson.getTopic(TOPIC);
    }

    @Override
    public void setUpdateCallback(Runnable callback) {
        listenerId = topic.addListener(String.class, (ch, msg) -> {
            if (!msg.startsWith(instanceId)) callback.run();
        });
    }

    @Override
    public void setUpdateCallback(Consumer<String> callback) {
        listenerId = topic.addListener(String.class, (ch, msg) -> {
            if (!msg.startsWith(instanceId)) callback.accept(msg);
        });
    }

    @Override
    public void update() {
        topic.publish(instanceId + ":reload");
    }

    @Override
    public void close() {
        if (listenerId != -1) topic.removeListener(listenerId);
    }
}
