package org.icij.datashare.text.nlp;

import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.mitie.MitiePipeline;

public class MitieNlpApp extends NlpApp {
    public static void main(String[] args) {
        new NlpApp().withNlp(MitiePipeline.class).withIndexer(ElasticsearchIndexer.class).run();
    }
}
