package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * filters the document queue with extracted docs
 */
public class DeduplicateTask extends DefaultTask<Integer> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private User user;
    private final RedisUserDocumentQueue queue;

    @Inject
    public DeduplicateTask(final PropertiesProvider propertiesProvider, @Assisted User user) {
        this.queue = new RedisUserDocumentQueue(user, Options.from(propertiesProvider.getProperties()));
        this.user = user;
    }

    @Override
    public Integer call() throws Exception {
        if (queue.size() == 0) {
            logger.info("filter empty queue {} nothing to do", queue.getName());
            return 0;
        }
        int duplicates = queue.removeDuplicatePaths();
        logger.info("removed {} duplicate paths in queue {}", duplicates, queue.getName());
        queue.close();
        return duplicates;
    }

    @Override
    public User getUser() {
        return user;
    }
}
