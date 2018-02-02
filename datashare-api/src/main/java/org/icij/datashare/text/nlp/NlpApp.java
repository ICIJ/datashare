package org.icij.datashare.text.nlp;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.icij.datashare.text.indexing.Indexer;

public class NlpApp implements Runnable {
    private Class<? extends AbstractPipeline> pipelineClass;
    private Class<? extends Indexer> indexerClass;

    public void run() {
        Injector injector = Guice.createInjector(new NlpModule(pipelineClass, indexerClass));
        DatashareListener listener = injector.getInstance(NlpDatashareListener.class);
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

    static class NlpModule extends AbstractModule {
        private final Class<? extends AbstractPipeline> pipelineClass;
        private final Class<? extends Indexer> indexerClass;

        NlpModule(Class<? extends AbstractPipeline> pipelineClass, Class<? extends Indexer> indexerClass) {
            this.pipelineClass = pipelineClass;
            this.indexerClass = indexerClass;
        }

        @Override
        public void configure() {
            bind(AbstractPipeline.class).to(pipelineClass);
            bind(Indexer.class).to(indexerClass);
        }
    }
}
