package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.extract.extractor.DocumentConsumer;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.DocumentQueueDrainer;
import org.icij.spewer.Spewer;
import org.icij.task.DefaultTask;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

@OptionsClass(Extractor.class)
@OptionsClass(DocumentQueueDrainer.class)
public class SpewTask extends DefaultTask<Long> implements Monitorable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentQueueDrainer drainer;
    private final DocumentConsumer consumer;
    private final DocumentQueue queue;
    private long totalToProcess;

    private Integer parallelism = Runtime.getRuntime().availableProcessors();

    @Inject
    public SpewTask(final Spewer spewer, final DocumentQueue queue, @Assisted final Options<String> userOptions) {
        userOptions.ifPresent("parallelism", o -> o.parse().asInteger()).ifPresent(this::setParallelism);
        this.queue = queue;
        Options<String> allTaskOptions = options().createFrom(userOptions);
        consumer = new DocumentConsumer(spewer, new Extractor().configure(allTaskOptions), this.parallelism);
        drainer = new DocumentQueueDrainer(queue, consumer).configure(allTaskOptions);
    }

    @Override
    public Long call() throws Exception {
        logger.info("Processing up to {} file(s) in parallel", parallelism);
        totalToProcess = drainer.drain().get();
        drainer.shutdown();
        drainer.awaitTermination(10, SECONDS); // drain is finished
        logger.info("drained {} documents. Waiting for consumer to shutdown", totalToProcess);
        consumer.shutdown();
        consumer.awaitTermination(30, MINUTES); // documents could be currently processed
        logger.info("exiting");
        return totalToProcess;
    }

    @Override
    public double getProgressRate() {
        totalToProcess = max(queue.size(), totalToProcess);
        return (double)(totalToProcess - queue.size()) / totalToProcess;
    }

    private void setParallelism(Integer integer) { this.parallelism = integer;}
}
