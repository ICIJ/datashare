package org.icij.datashare.extract;

import org.icij.datashare.user.User;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.redis.RedisDocumentQueue;
import org.icij.task.Option;
import org.icij.task.Options;
import org.icij.task.StringOptionParser;

public class RedisUserDocumentQueue extends RedisDocumentQueue {
    private static final String QUEUE_NAME = "queueName";

    public RedisUserDocumentQueue(final User user, final Options<String> options) {
        super(new DocumentFactory(options), user == null ? options: createOrUpdateQueueName(options, user));
    }

    private static Options<String> createOrUpdateQueueName(Options<String> userOptions, User user) {
        if (userOptions.get(QUEUE_NAME) == null) {
            userOptions.add(new Option<>(QUEUE_NAME, StringOptionParser::new).update("extract:queue"));
        }
        String userQueueName = userOptions.get(QUEUE_NAME).value().get() + "_" + user.id;
        return userOptions.createFrom(
                new Options<String>()
                        .add(new Option<>(QUEUE_NAME, StringOptionParser::new).update(userQueueName)));
    }
}
