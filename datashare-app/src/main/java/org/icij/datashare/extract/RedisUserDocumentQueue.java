package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedisDocumentQueue;
import org.jetbrains.annotations.NotNull;
import org.redisson.RedissonShutdownException;

import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPTION;

public class RedisUserDocumentQueue extends RedisDocumentQueue {
    private final String queueName;

    @Inject
    public RedisUserDocumentQueue(PropertiesProvider propertiesProvider, @Assisted String queueName) {
        super(queueName, propertiesProvider.get("redisAddress").orElse("redis://redis:6379"));
        this.queueName = queueName;
    }

    public RedisUserDocumentQueue(@NotNull final User user, PropertiesProvider propertiesProvider) {
        this(propertiesProvider, getQueueName(user, propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue")));
    }

    public RedisUserDocumentQueue(PropertiesProvider propertiesProvider) {
        this(User.nullUser(), propertiesProvider);
    }

    @Override
    public int size() {
        try {
            return super.size();
        } catch (RedissonShutdownException e) {
            return -1;
        }
    }

    public String getQueueName() {
        return queueName;
    }
    private static String getQueueName(User user, String baseQueueName) {
        return user.isNull() ? baseQueueName : user.queueName();
    }
}
