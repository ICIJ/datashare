package org.icij.datashare.text.nlp;

import com.google.inject.AbstractModule;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.opennlp.OpennlpPipeline;

public class ProdModule extends AbstractModule {
    @Override
    public void configure() {
        bind(AbstractPipeline.class).to(OpennlpPipeline.class);
        bind(Indexer.class).to(ElasticsearchIndexer.class);
    }
}
