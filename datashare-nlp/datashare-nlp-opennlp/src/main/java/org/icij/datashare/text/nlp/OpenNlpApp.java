package org.icij.datashare.text.nlp;

import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.opennlp.OpennlpPipeline;

public class OpenNlpApp extends NlpApp {
    public static void main(String[] args) {
        new NlpApp().withNlp(OpennlpPipeline.class).withIndexer(ElasticsearchIndexer.class).run();
    }
}
