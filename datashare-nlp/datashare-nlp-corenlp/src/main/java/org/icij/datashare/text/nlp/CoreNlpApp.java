package org.icij.datashare.text.nlp;

import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.corenlp.CorenlpPipeline;

public class CoreNlpApp extends NlpApp {
    public static void main(String[] args) {
        new NlpApp().withNlp(CorenlpPipeline.class).withIndexer(ElasticsearchIndexer.class).run();
    }
}
