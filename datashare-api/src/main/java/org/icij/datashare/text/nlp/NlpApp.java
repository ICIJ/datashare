package org.icij.datashare.text.nlp;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Message;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.*;

import static java.lang.Integer.parseInt;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Stream.generate;

public class NlpApp implements Runnable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    public static final String NLP_PARALLELISM_OPT = "nlpParallelism";
    private static final int DEFAULT_QUEUE_SIZE = 10000;
    private final AbstractPipeline pipeline;
    private final Indexer indexer;
    private Runnable subscribedCb;
    private Properties properties;
    private BlockingQueue<Message> queue;
    private ExecutorService threadPool = null;
    private final int parallelism;

    @Inject
    public NlpApp(final Indexer indexer, @Assisted final AbstractPipeline pipeline, final PropertiesProvider propertiesProvider) {
        this.pipeline = pipeline;
        this.indexer = indexer;
        this.queue = new ArrayBlockingQueue<>(DEFAULT_QUEUE_SIZE, true);
        this.properties = propertiesProvider.getProperties();
        parallelism = parseInt(ofNullable(properties.getProperty(NLP_PARALLELISM_OPT)).orElse("1"));
        subscribedCb = () -> {};
    }

    NlpApp(final Indexer indexer, final AbstractPipeline pipeline, final PropertiesProvider propertiesProvider, Runnable subscribedCb) {
        this(indexer, pipeline, propertiesProvider);
        this.subscribedCb = subscribedCb;
    }

    public void run() {
        try {
            logger.info("running NlpApp for {} pipeline with {} thread(s)", pipeline.getType(), parallelism);
            this.threadPool = Executors.newFixedThreadPool(parallelism,
                    new ThreadFactoryBuilder().setNameFormat(pipeline.getType().name() + "-%d").build());
            generate(() -> new NlpDatashareConsumer(pipeline, indexer, queue)).limit(parallelism).forEach(l -> threadPool.execute(l));
            NlpDatashareForwarder forwarder = new NlpDatashareForwarder(properties, queue, subscribedCb);
            forwarder.run();
            logger.info("forwarder exited waiting for consumer(s) to finish");
            threadPool.shutdown();
            threadPool.awaitTermination(10, SECONDS);
        } catch (Throwable throwable) {
            logger.error("error running NlpApp", throwable);
        }
    }

    public static class NlpModule extends AbstractModule {
        private final Class<? extends AbstractPipeline> pipelineClass;
        private final Class<? extends Indexer> indexerClass;

        NlpModule(Class<? extends AbstractPipeline> pipelineClass, Class<? extends Indexer> indexerClass) {
            this.pipelineClass = pipelineClass;
            this.indexerClass = indexerClass;
        }

        @Override
        public void configure() {
            bind(PropertiesProvider.class).toInstance(new PropertiesProvider());
            bind(AbstractPipeline.class).to(pipelineClass).asEagerSingleton();
            bind(Indexer.class).to(indexerClass).asEagerSingleton();
            install(new FactoryModuleBuilder().build(NlpAppFactory.class));
        }
        interface NlpAppFactory {
            NlpApp createNlpApp(AbstractPipeline pipeline);
        }
    }
}
