package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * filters the document queue with extracted docs
 */
public class DeduplicateTask extends PipelineTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Inject
    public DeduplicateTask(final DocumentCollectionFactory factory, final PropertiesProvider propertiesProvider, @Assisted User user, @Assisted String queueName) {
        super(DatashareCli.Stage.DEDUPLICATE, user, queueName, factory, propertiesProvider);
    }

    @Override
    public Long call() throws Exception {
        int duplicates = queue.removeDuplicates();
        transferToOutputQueue();
        logger.info("removed {} duplicate paths in queue {}", duplicates, queue.getName());
        queue.close();
        return (long)duplicates;
    }
}
