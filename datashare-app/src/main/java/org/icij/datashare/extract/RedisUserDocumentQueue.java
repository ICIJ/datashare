package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedisDocumentQueue;
import org.icij.extract.redis.RedissonClientFactory;
import org.icij.task.Options;
import org.redisson.RedissonShutdownException;
import org.redisson.api.RedissonClient;

import java.nio.charset.Charset;

import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPT;

public class RedisUserDocumentQueue<T> extends RedisDocumentQueue<T> {
    private final String queueName;

    @Inject
    public RedisUserDocumentQueue(PropertiesProvider propertiesProvider, RedissonClient redissonClient, @Assisted String queueName, @Assisted Class<T> clazz) {
        super(redissonClient, queueName, Charset.forName(propertiesProvider.get("charset").orElse(Charset.defaultCharset().toString())), clazz);
        this.queueName = queueName;
    }

    public RedisUserDocumentQueue(final User user, PropertiesProvider propertiesProvider, Class<T> clazz) {
        this(propertiesProvider, new RedissonClientFactory().withOptions(Options.from(propertiesProvider.getProperties())).create(),
                getQueueName(user, propertiesProvider.get(QUEUE_NAME_OPT).orElse("extract:queue")), clazz);
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
