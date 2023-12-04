package org.icij.datashare.nlp;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.DataBus;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.ShutdownMessage;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import static java.lang.Integer.parseInt;
import static java.lang.Integer.toHexString;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.generate;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PARALLELISM_OPT;

public class NlpApp implements Callable<Integer>, Monitorable, UserTask {
    private static final long DEFAULT_TIMEOUT_MILLIS = 30 * 60 * 1000;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Pipeline pipeline;
    private final Indexer indexer;
    private final long shutdownTimeoutMillis;
    private final boolean cleanShutdown;
    private final BlockingQueue<Message> queue;
    private final int parallelism;
    private final NlpForwarder forwarder;
    private final User user;
    private ExecutorService threadPool = null;

    @AssistedInject
    public NlpApp(final DataBus dataBus, final Indexer indexer, final PropertiesProvider propertiesProvider, @Assisted final Pipeline pipeline, @Assisted final User user) {
        this(dataBus, indexer, pipeline, propertiesProvider.getProperties(), () -> {}, 0, false, user);
    }

    @AssistedInject
    public NlpApp(final DataBus dataBus, final Indexer indexer, @Assisted final Pipeline pipeline, @Assisted final Properties properties,
                  @Assisted final User user, @Assisted final Runnable subscribeCb) {
        this(dataBus, indexer, pipeline, properties, subscribeCb, 0, false, user);
    }

    NlpApp(final DataBus dataBus, final Indexer indexer, final Pipeline pipeline, final Properties properties,
           Runnable subscribedCb, long shutdownTimeoutMillis, boolean cleanShutdown, User user) {
        this.pipeline = pipeline;
        this.indexer = indexer;
        this.shutdownTimeoutMillis = shutdownTimeoutMillis == 0 ? DEFAULT_TIMEOUT_MILLIS : shutdownTimeoutMillis;
        this.cleanShutdown = cleanShutdown;
        this.queue = new LinkedBlockingQueue<>();
        this.user = user;

        parallelism = parseInt(ofNullable(properties.getProperty(NLP_PARALLELISM_OPT)).orElse("1"));
        forwarder = new NlpForwarder(dataBus, queue, subscribedCb);
    }

    public Integer call() {
        int nbNlp = 0;
        try {
            logger.info("running NlpApp for {} pipeline with {} thread(s)", pipeline.getType(), parallelism);
            this.threadPool = Executors.newFixedThreadPool(parallelism,
                    new ThreadFactoryBuilder().setNameFormat(pipeline.getType().name() + "-%d").build());
            generate(() -> new NlpConsumer(pipeline, indexer, queue)).limit(parallelism).forEach(l -> threadPool.submit(l));
            nbNlp = forwarder.call();
            logger.info("forwarder exited waiting for consumer(s) to finish");
            shutdown();
        } catch (Throwable throwable) {
            logger.error("error running NlpApp", throwable);
        }
        logger.info("exiting run processed {} messages", nbNlp);
        return nbNlp;
    }

    private void shutdown() throws InterruptedException {
        waitForQueueToBeEmpty();
        threadPool.shutdown();
        generate(() -> queue.offer(new ShutdownMessage())).limit(parallelism).collect(toList()); // trying to clean exit
        boolean threadPoolExited = false;
        while (!(threadPoolExited = threadPool.awaitTermination(shutdownTimeoutMillis, MILLISECONDS))) {
            if (cleanShutdown) {
                // Loop-wait for a clean shutdown
                logger.info("consumers have not finished yet. Waiting...");
            } else {
                logger.info("consumers have not finished yet, interrupting...");
                threadPool.shutdownNow();
                if (! threadPool.awaitTermination(shutdownTimeoutMillis, MILLISECONDS)) {
                    logger.info("consumers still not interrupted (should maybe hit CTRL-C to exit)");
                } else {
                    logger.info("consumers interrupted");
                }
                // break out of the wait loop
                break;
            }
        }
    }

    private void waitForQueueToBeEmpty() throws InterruptedException {
        if (! queue.isEmpty()) {
            synchronized (queue) {
                queue.wait();
            }
        }
    }

    @Override
    public double getProgressRate() {
        return forwarder.getProgressRate();
    }

    @Override
    public String toString() {
        return getClass().getName() + "[" + pipeline.getType() + "]@" + toHexString(hashCode());
    }

    @Override
    public User getUser() {
        return user;
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
