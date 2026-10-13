package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.spewer.FieldNames;

/** Builds one immutable {@link ElasticsearchSpewer} per run: the index, the OCR threshold and the
 *  manifest recorder are known to the task, not to the injector. */
public class ElasticsearchSpewerFactory {
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
        return new ElasticsearchSpewer(indexer, outputQueueFactory, languageGuesser, fields, propertiesProvider,
                                       indexName, minOcrConfidence, manifestRecorder);
    }
}
