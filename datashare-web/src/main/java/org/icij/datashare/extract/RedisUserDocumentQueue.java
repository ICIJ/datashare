package org.icij.datashare.extract;

import org.icij.datashare.User;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.redis.RedisDocumentQueue;
import org.icij.task.Option;
import org.icij.task.Options;
import org.icij.task.StringOptionParser;


public class RedisUserDocumentQueue extends RedisDocumentQueue {
    public RedisUserDocumentQueue(final User user, final Options<String> options) {
        super(new DocumentFactory(options), user == null ? options: createOrUpdateQueueName(options, user));
    }

    private static Options<String> createOrUpdateQueueName(Options<String> userOptions, User user) {
        if (userOptions.get("queueName") == null) {
            userOptions.add(new Option<>("queueName", StringOptionParser::new).update("extract:queue"));
        }
        String userQueueName = userOptions.get("queueName").value().get() + "_" + user.id;
        return userOptions.createFrom(
                new Options<String>()
                        .add(new Option<>("queueName", StringOptionParser::new).update(userQueueName)));
    }
}
