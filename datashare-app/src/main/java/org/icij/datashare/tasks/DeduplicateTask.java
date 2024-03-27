package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * filters the document queue with extracted docs
 */
public class DeduplicateTask extends PipelineTask<Path> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentCollectionFactory<Path> factory;

    @Inject
    public DeduplicateTask(final DocumentCollectionFactory<Path> factory, @Assisted TaskView<Long> taskView, @Assisted final BiFunction<String, Double, Void> updateCallback) {
        super(Stage.DEDUPLICATE, taskView.user, factory, new PropertiesProvider(taskView.properties), Path.class);
        this.factory = factory;
    }

    @Override
    public Long call() throws Exception {
        super.call();
        int duplicates = inputQueue.removeDuplicates();
        transferToOutputQueue();
        logger.info("removed {} duplicate paths in inputQueue {}", duplicates, inputQueue.getName());
        return (long)duplicates;
    }

    long transferToOutputQueue() throws Exception {
        return transferToOutputQueue(p -> true);
    }

    long transferToOutputQueue(Predicate<Path> filter) throws Exception {
        long originalSize = inputQueue.size();
        try (DocumentQueue<Path> outputQueue = factory.createQueue(getOutputQueueName(), Path.class)) {
            Path path;
            while (!(path = inputQueue.take()).equals(PATH_POISON)) {
                if (filter.test(path)) {
                    outputQueue.add(path);
                }
            }
            outputQueue.add(PATH_POISON);
            return originalSize - outputQueue.size();
        }
    }
}
