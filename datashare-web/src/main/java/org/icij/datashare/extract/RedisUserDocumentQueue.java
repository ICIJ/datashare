package org.icij.datashare.extract;

import org.icij.datashare.user.User;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.redis.RedisDocumentQueue;
import org.icij.task.Option;
import org.icij.task.Options;
import org.icij.task.StringOptionParser;
import org.jetbrains.annotations.NotNull;
import org.redisson.RedissonShutdownException;
import org.slf4j.LoggerFactory;

public class RedisUserDocumentQueue extends RedisDocumentQueue {
    private static final String QUEUE_NAME = "queueName";

    public RedisUserDocumentQueue(@NotNull final User user, final Options<String> options) {
        super(new DocumentFactory(options), createOrUpdateQueueNameInOptions(options, user));
    }

    private static Options<String> createOrUpdateQueueNameInOptions(Options<String> userOptions, User user) {
        if (userOptions.get(QUEUE_NAME) == null) {
            userOptions.add(new Option<>(QUEUE_NAME, StringOptionParser::new).update("extract:queue"));
        }
        String queueName = user.isNull() ? userOptions.valueIfPresent(QUEUE_NAME).orElse("extract:queue") : user.queueName();
        LoggerFactory.getLogger(RedisDocumentQueue.class).info("using redis queue name {}", queueName);
        Options<String> options = userOptions.createFrom(new Options<String>()
                .add(new Option<>(QUEUE_NAME, StringOptionParser::new).update(queueName)));
        return options;
    }

    @Override
    public int size() {
        try {
            return super.size();
        } catch (RedissonShutdownException e) {
            return -1;
        }
    }
}
