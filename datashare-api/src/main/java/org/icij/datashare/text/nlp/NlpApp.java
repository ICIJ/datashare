package org.icij.datashare.text.nlp;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.indexing.Indexer;

import java.util.Properties;

public class NlpApp implements Runnable {
    private Class<? extends AbstractPipeline> pipelineClass;
    private Class<? extends Indexer> indexerClass;
    private Properties properties;

    public void run() {
        Injector injector = Guice.createInjector(new NlpModule(pipelineClass, indexerClass, properties));
        DatashareListener listener = injector.getInstance(NlpDatashareSubscriber.class);
        listener.waitForEvents();
    }

    public NlpApp withNlp(Class<? extends AbstractPipeline> pipelineClass) {
        this.pipelineClass = pipelineClass;
        return this;
    }

    public NlpApp withIndexer(Class<? extends Indexer> indexerClass) {
        this.indexerClass = indexerClass;
        return this;
    }

    public NlpApp withProperties(Properties properties) {
        this.properties = properties;
        return this;
    }

    static class NlpModule extends AbstractModule {
        private final Class<? extends AbstractPipeline> pipelineClass;
        private final Class<? extends Indexer> indexerClass;
        private final Properties properties;

        NlpModule(Class<? extends AbstractPipeline> pipelineClass, Class<? extends Indexer> indexerClass, Properties properties) {
            this.pipelineClass = pipelineClass;
            this.indexerClass = indexerClass;
            this.properties = properties;
        }

        @Override
        public void configure() {
            if (properties != null) {
                bind(PropertiesProvider.class).toInstance(new PropertiesProvider(properties));
            }
            bind(AbstractPipeline.class).to(pipelineClass);
            bind(Indexer.class).to(indexerClass);
        }
    }
}
