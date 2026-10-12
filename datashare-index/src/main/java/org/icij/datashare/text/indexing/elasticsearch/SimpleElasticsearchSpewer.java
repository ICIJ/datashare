package org.icij.datashare.text.indexing.elasticsearch;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.spewer.FieldNames;

public class SimpleElasticsearchSpewer extends AbstractElasticSearchSpewer {
    public SimpleElasticsearchSpewer(FieldNames fields, Indexer indexer, LanguageGuesser languageGuesser,
                                     IndexOptions options) {
        super(fields, indexer, languageGuesser, options);
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
