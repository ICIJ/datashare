package org.icij.datashare.text.nlp;

import com.google.inject.Injector;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.opennlp.OpennlpPipeline;

import static com.google.inject.Guice.createInjector;

public class OpenNlpApp {
    public static void main(String[] args) {
        Injector injector = createInjector(new NlpApp.NlpModule(OpennlpPipeline.class, ElasticsearchIndexer.class));
        injector.getInstance(NlpApp.NlpModule.NlpAppFactory.class).createNlpApp(injector.getInstance(AbstractPipeline.class)).run();
    }
}
