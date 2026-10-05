package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
import java.io.Serializable;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import org.icij.datashare.Stage;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_OCR_MIN_CONFIDENCE;
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
    public String indexName;
    // volatile: set by the task thread before the pipeline starts, read by every consumer thread
    private volatile ManifestRecorder manifestRecorder;
    // volatile: set by the task thread in configure, read by every consumer thread
    private volatile int minOcrConfidence = DEFAULT_OCR_MIN_CONFIDENCE;

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
