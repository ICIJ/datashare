package org.icij.datashare.text.nlp;

import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.gatenlp.GatenlpPipeline;

public class GateNlpApp extends NlpApp {
    public static void main(String[] args) {
        new NlpApp().withNlp(GatenlpPipeline.class).withIndexer(ElasticsearchIndexer.class).run();
    }
}
