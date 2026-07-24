package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
import org.icij.datashare.*;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.*;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.FieldNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.Serializable;

public class ElasticsearchSpewer extends AbstractElasticSearchSpewer implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchSpewer.class);
    private final DocumentQueue<String> outputQueue;

    @Inject
    public ElasticsearchSpewer(final Indexer indexer, DocumentCollectionFactory<String> outputQueueFactory,
                               LanguageGuesser languageGuesser, final FieldNames fields,
                               final PropertiesProvider propertiesProvider) {
        super(fields, indexer, languageGuesser, IndexOptions.fromPropertiesProvider(propertiesProvider));
        this.outputQueue = outputQueueFactory.createQueue(
                new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        this.indexName = propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT);
        logger.info("spewer defined with {}", indexer);
    }

    @Override
    void postIndexation(Document document, String index) {
        String queueEntry = DocReference.fromDocument(document).toQueueEntry();
        if (!outputQueue.offer(queueEntry)) {
            logger.warn("cannot offer {} to queue {}", queueEntry, outputQueue.getName());
        }
    }

    @Override
    public void close() throws InterruptedException {
        //Nothing to do
    }
}
