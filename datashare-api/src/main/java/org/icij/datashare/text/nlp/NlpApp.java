package org.icij.datashare.text.nlp;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Message;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.stream.Stream.generate;

public class NlpApp implements Runnable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    public static final String NLP_PARALLELISM_OPT = "nlpParallelism";
    private static final int DEFAULT_QUEUE_SIZE = 10000;
    private Class<? extends AbstractPipeline> pipelineClass;
    private Class<? extends Indexer> indexerClass;
    private Properties properties;
    private Class<? extends NlpDatashareListener> nlpClass = NlpDatashareSubscriber.class;
    private BlockingQueue<Message> queueInstance;
    private ExecutorService threadPool = null;
    private int parallelism = 1;

    public void run() {
        try {
            Injector injector = Guice.createInjector(new NlpModule(this));
            logger.info("running NlpApp for {} pipeline with {} thread(s)", pipelineClass, parallelism);
            if (this.isInServerMode()) {
                AbstractPipeline pipeline = injector.getInstance(AbstractPipeline.class);
                this.threadPool = Executors.newFixedThreadPool(parallelism,
                        new ThreadFactoryBuilder().setNameFormat(pipeline.getType().name() + "-%d").build());
                generate(() -> injector.getInstance(nlpClass)).limit(parallelism).forEach(l -> threadPool.execute(l));
                NlpDatashareForwarder forwarder = injector.getInstance(NlpDatashareForwarder.class);
                forwarder.run();
            } else {
                DatashareListener listener = injector.getInstance(nlpClass);
                listener.run();
            }
        } catch (Throwable throwable) {
            logger.error("error running NlpApp", throwable);
        }
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
        if (properties.get(NLP_PARALLELISM_OPT) != null) {
            this.inServerMode(Integer.parseInt(properties.getProperty(NLP_PARALLELISM_OPT)))
                    .withNlpListener(NlpDatashareConsumer.class);
        }
        return this;
    }

    public NlpApp withNlpListener(Class<? extends NlpDatashareListener> nlpClass) {
        this.nlpClass = nlpClass;
        return this;
    }

    public NlpApp inServerMode(int parallelism) {
        this.parallelism = parallelism;
        this.queueInstance = new ArrayBlockingQueue<>(DEFAULT_QUEUE_SIZE, true);
        return this;
    }

    private boolean isInServerMode() { return parallelism > 1;}

    public static class NlpModule extends AbstractModule {
        private final NlpApp nlpApp;

        NlpModule(NlpApp nlpApp) {
            this.nlpApp = nlpApp;
        }

        @Override
        public void configure() {
            if (nlpApp.properties != null) {
                bind(PropertiesProvider.class).toInstance(new PropertiesProvider(nlpApp.properties));
            }
            if (nlpApp.queueInstance != null) {
                bind(new TypeLiteral<BlockingQueue<Message>>() {}).toInstance(nlpApp.queueInstance);
            }
            bind(NlpDatashareListener.class).to(nlpApp.nlpClass);
            bind(AbstractPipeline.class).to(nlpApp.pipelineClass).asEagerSingleton();
            bind(Indexer.class).to(nlpApp.indexerClass).asEagerSingleton();
        }
    }
}
