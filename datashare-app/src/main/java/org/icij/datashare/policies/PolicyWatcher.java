package org.icij.datashare.policies;

import org.casbin.jcasbin.persist.Watcher;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.UUID;
import java.util.function.Consumer;

public class PolicyWatcher implements Watcher, Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyWatcher.class);
    private static final String TOPIC = "datashare:policy-updated";
    private final RTopic topic;
    // Unique per-instance ID so each node ignores its own publications.
    // Redisson delivers messages to all subscribers on the topic including the
    // publisher itself; without this guard, addGroupingPolicy() would trigger
    // an unnecessary loadPolicy() reload on the very instance that just wrote
    // the change, momentarily clearing the in-memory model.
    private final String instanceId = UUID.randomUUID().toString();
    private volatile int listenerId = -1;

    public PolicyWatcher(RedissonClient redisson) {
        this.topic = redisson.getTopic(TOPIC);
    }

    @Override
    public void setUpdateCallback(Runnable callback) {
        if (listenerId != -1) topic.removeListener(listenerId);
        listenerId = topic.addListener(PolicyUpdateMessage.class, (ch, msg) -> {
            if (instanceId.equals(msg.callerId())) {
                LOGGER.debug("Ignoring own policy-update notification");
            } else {
                LOGGER.info("Received policy-update notification from remote instance, reloading");
                callback.run();
            }
        });
    }

    @Override
    public void setUpdateCallback(Consumer<String> callback) {
        if (listenerId != -1) topic.removeListener(listenerId);
        listenerId = topic.addListener(PolicyUpdateMessage.class, (ch, msg) -> {
            if (instanceId.equals(msg.callerId())) {
                LOGGER.debug("Ignoring own policy-update notification");
            } else {
                LOGGER.info("Received policy-update notification from remote instance, reloading");
                callback.accept(msg.message());
            }
        });
    }

    @Override
    public void update() {
        try {
            topic.publish(new PolicyUpdateMessage(instanceId, "reload"));
        } catch (Exception e) {
            // The Casbin rule was already written to SQL; only the Redis notification failed.
            // Do not rethrow: that would unwind casbinWithRollback and corrupt the
            // inventory/Casbin state (SQL rule committed, inventory rolled back).
            // The operator must restart other instances or wait for auto-reload to catch up.
            LOGGER.warn("Policy-reload notification failed; other server instances will not"
                    + " see this grant until restarted or auto-reload fires. Cause: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (listenerId != -1) topic.removeListener(listenerId);
    }
}
