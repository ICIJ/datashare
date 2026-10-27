package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.spewer.FieldNames;

public class SimpleElasticsearchSpewer extends AbstractElasticSearchSpewer {
    public SimpleElasticsearchSpewer(FieldNames fields, Indexer indexer, LanguageGuesser languageGuesser,
                                     IndexOptions options, String indexName, ManifestRecorder manifestRecorder) {
        super(fields, indexer, languageGuesser, options, indexName, options.languageDetection().ocrMinConfidence(), manifestRecorder);
    }

    @Override
    public void close() {
        // Not closing the connection to be used later
    }
}
