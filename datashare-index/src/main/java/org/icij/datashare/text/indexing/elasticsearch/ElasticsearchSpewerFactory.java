package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
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

/** Builds one {@link ElasticsearchSpewer} per run: the index, the OCR threshold and the manifest recorder are known
 *  to the task, not to the injector. The spewer hands each indexed document to the queue of the next stage, the
 *  listener a stage replaces with its {@code emit} when it runs the spewer itself. */
public class ElasticsearchSpewerFactory {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchSpewerFactory.class);
    private final Indexer indexer;
    private final DocumentCollectionFactory<String> outputQueueFactory;
    private final LanguageGuesser languageGuesser;
    private final FieldNames fields;
    private final PropertiesProvider propertiesProvider;

    @Inject
    public ElasticsearchSpewerFactory(Indexer indexer, DocumentCollectionFactory<String> outputQueueFactory,
                                      LanguageGuesser languageGuesser, FieldNames fields,
                                      PropertiesProvider propertiesProvider) {
        this.indexer = indexer;
        this.outputQueueFactory = outputQueueFactory;
        this.languageGuesser = languageGuesser;
        this.fields = fields;
        this.propertiesProvider = propertiesProvider;
    }

    /** @param manifestRecorder null when the run does not generate artifacts */
    public ElasticsearchSpewer create(String indexName, int minOcrConfidence, ManifestRecorder manifestRecorder) {
        ElasticsearchSpewer spewer =
                new ElasticsearchSpewer(fields, indexer, languageGuesser, IndexOptions.of(propertiesProvider),
                                        indexName, minOcrConfidence, manifestRecorder);
        DocumentQueue<String> outputQueue = outputQueueFactory.createQueue(
                new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        spewer.onIndexed(document -> offerToNextStage(outputQueue, document));
        logger.info("spewer defined with {} on index {}", indexer, indexName);
        return spewer;
    }

    private static void offerToNextStage(DocumentQueue<String> outputQueue, Document document) {
        String queueEntry = DocReference.fromDocument(document).toQueueEntry();
        if (!outputQueue.offer(queueEntry)) {
            logger.warn("cannot offer {} to queue {}", queueEntry, outputQueue.getName());
        }
    }
}
