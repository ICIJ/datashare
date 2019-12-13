package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchExtractedStreamer;
import org.icij.datashare.user.User;
import org.icij.extract.QueueFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * filters the document queue with extracted docs
 */
public class FilterTask extends PipelineTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final String projectName;
    private Indexer indexer;

    @Inject
    public FilterTask(final Indexer indexer, final PropertiesProvider propertiesProvider, @Assisted User user) {
        super(DatashareCli.Stage.FILTER, user, propertiesProvider);
        this.projectName = propertiesProvider.get("defaultProject").orElse("local-datashare");
        this.indexer = indexer;
    }

    @Override
    public Long call() throws Exception {
        if (queue.size() == 0) {
            logger.info("filter empty queue {} nothing to do", queue.getName());
            return 0L;
        }
        long extracted = new QueueFilterBuilder()
                .filter(queue)
                .with(new ElasticsearchExtractedStreamer(indexer, projectName))
                .execute();
        logger.info("removed {} extracted paths in queue {}", extracted, queue.getName());
        queue.close();
        return extracted;
    }
}
