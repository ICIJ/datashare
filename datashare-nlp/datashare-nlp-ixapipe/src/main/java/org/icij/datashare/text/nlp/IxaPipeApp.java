package org.icij.datashare.text.nlp;

import com.google.inject.Injector;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.ixapipe.IxapipePipeline;

import static com.google.inject.Guice.createInjector;

public class IxaPipeApp {
    public static void main(String[] args) {
        Injector injector = createInjector(new NlpApp.NlpModule(IxapipePipeline.class, ElasticsearchIndexer.class));
        injector.getInstance(NlpApp.NlpModule.NlpAppFactory.class).createNlpApp(injector.getInstance(AbstractPipeline.class)).run();
    }
}
