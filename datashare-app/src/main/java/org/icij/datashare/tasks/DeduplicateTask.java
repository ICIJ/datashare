package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * filters the document queue with extracted docs
 */
public class DeduplicateTask extends PipelineTask<Path> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentCollectionFactory<Path> factory;

    @Inject
    public DeduplicateTask(final DocumentCollectionFactory<Path> factory, final PropertiesProvider propertiesProvider, @Assisted User user, @Assisted String queueName) {
        super(DatashareCli.Stage.DEDUPLICATE, user, queueName, factory, propertiesProvider, Path.class);
        this.factory = factory;
    }

    @Override
    public Long call() throws Exception {
        int duplicates = queue.removeDuplicates();
        transferToOutputQueue();
        logger.info("removed {} duplicate paths in queue {}", duplicates, queue.getName());
        queue.close();
        return (long)duplicates;
    }

    long transferToOutputQueue() throws Exception {
        return transferToOutputQueue(p -> true);
    }

    long transferToOutputQueue(Predicate<Path> filter) throws Exception {
        long originalSize = queue.size();
        try (DocumentQueue<Path> outputQueue = factory.createQueue(propertiesProvider, getOutputQueueName(), Path.class)) {
            Path path;
            while (!(path = queue.take()).equals(PATH_POISON)) {
                if (filter.test(path)) {
                    outputQueue.add(path);
                }
            }
            outputQueue.add(PATH_POISON);
            return originalSize - outputQueue.size();
        }
    }
}
