package org.icij.datashare.text.nlp;

import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.ixapipe.IxapipePipeline;

public class IxaPipeApp extends NlpApp {
    public static void main(String[] args) {
        new NlpApp().withNlp(IxapipePipeline.class).withIndexer(ElasticsearchIndexer.class).run();
    }
}
