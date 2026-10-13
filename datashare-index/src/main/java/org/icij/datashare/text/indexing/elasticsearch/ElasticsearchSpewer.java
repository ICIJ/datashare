package org.icij.datashare.text.indexing.elasticsearch;

import java.io.Serializable;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.FieldNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ElasticsearchSpewer extends AbstractElasticSearchSpewer implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchSpewer.class);
    private final DocumentQueue<String> outputQueue;

    public ElasticsearchSpewer(final Indexer indexer, DocumentCollectionFactory<String> outputQueueFactory,
                               LanguageGuesser languageGuesser, final FieldNames fields,
                               final PropertiesProvider propertiesProvider, String indexName, int minOcrConfidence,
                               ManifestRecorder manifestRecorder) {
        super(fields, indexer, languageGuesser, IndexOptions.fromPropertiesProvider(propertiesProvider), indexName,
              minOcrConfidence, manifestRecorder);
        this.outputQueue = outputQueueFactory.createQueue(
                new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        logger.info("spewer defined with {} on index {}", indexer, indexName);
    }

    @Override
    protected void postIndexation(Document document, String index) {
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
