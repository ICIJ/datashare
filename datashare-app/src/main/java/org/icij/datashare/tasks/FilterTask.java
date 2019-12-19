package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedisDocumentSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * filters the document queue with a set
 */
public class FilterTask extends PipelineTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final RedisDocumentSet filterSet;

    @Inject
    public FilterTask(final PropertiesProvider propertiesProvider, @Assisted User user) {
        super(DatashareCli.Stage.FILTER, user, propertiesProvider);
        this.filterSet = new RedisDocumentSet(
                propertiesProvider.get("filterSet").orElseThrow(() -> new IllegalArgumentException("no filterSet property defined")),
                propertiesProvider.get("redisAddress").orElse("redis://redis:6379"));
    }

    @Override
    public Long call() throws Exception {
        long extracted  = transferToOutputQueue(p -> !filterSet.contains(p));
        logger.info("filtered {} paths from queue {} to {}", extracted, queue.getName(), getOutputQueueName());
        queue.close();
        filterSet.close();
        return extracted;
    }
}
