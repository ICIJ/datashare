package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.spewer.FieldNames;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_OCR_MIN_CONFIDENCE;

public class SimpleElasticsearchSpewer extends AbstractElasticSearchSpewer {
    public SimpleElasticsearchSpewer(FieldNames fields, Indexer indexer, LanguageGuesser languageGuesser,
                                     IndexOptions options, String indexName, ManifestRecorder manifestRecorder) {
        super(fields, indexer, languageGuesser, options, indexName, DEFAULT_OCR_MIN_CONFIDENCE, manifestRecorder);
    }

    @Override
    protected void postIndexation(Document document, String indexName) {
        // No event to generate, the workflow can continue with data in ES
    }

    @Override
    public void close() {
        // Not closing the connection to be used later
    }
}
