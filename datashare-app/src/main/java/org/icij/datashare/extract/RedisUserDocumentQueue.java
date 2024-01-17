package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedisDocumentQueue;
import org.redisson.RedissonShutdownException;

import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPTION;

public class RedisUserDocumentQueue<T> extends RedisDocumentQueue<T> {
    private final String queueName;

    @Inject
    public RedisUserDocumentQueue(PropertiesProvider propertiesProvider, @Assisted String queueName, @Assisted Class<T> clazz) {
        super(queueName, propertiesProvider.get("redisAddress").orElse("redis://redis:6379"), clazz);
        this.queueName = queueName;
    }

    public RedisUserDocumentQueue(final User user, PropertiesProvider propertiesProvider, Class<T> clazz) {
        this(propertiesProvider, getQueueName(user, propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue")), clazz);
    }

    public RedisUserDocumentQueue(PropertiesProvider propertiesProvider, Class<T> clazz) {
        this(User.nullUser(), propertiesProvider, clazz);
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
