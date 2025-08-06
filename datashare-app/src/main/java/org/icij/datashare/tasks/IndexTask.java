package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.DocumentConsumer;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueueDrainer;
import org.icij.extract.report.Reporter;
import org.icij.task.Options;
import org.icij.task.annotation.Option;
import org.icij.task.annotation.OptionsClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

import static java.lang.Math.max;
import static java.lang.String.valueOf;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.PropertiesProvider.REPORT_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.*;

@OptionsClass(Extractor.class)
@OptionsClass(DocumentFactory.class)
@OptionsClass(DocumentQueueDrainer.class)
@Option(name = DEFAULT_PROJECT_OPT, description = "the default project name")
@Option(name = "projectName", description = "task project name")
@TaskGroup(TaskGroupType.Java)
public class IndexTask extends PipelineTask<Path> implements Monitorable{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentQueueDrainer<Path> drainer;
    private final DocumentConsumer consumer;
    private final Function<Double, Void> progressCallback;
    private final Consumer<Path> progressTrackConsumer;
    private long totalToProcess;

    private final AtomicInteger processed = new AtomicInteger(0);
    private final Integer parallelism;
    private final Integer indexTimeout;

    @Inject
    public IndexTask(final ElasticsearchSpewer spewer, final DocumentCollectionFactory<Path> factory, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progressCallback) throws IOException {
        super(Stage.INDEX, taskView.getUser(), factory, new PropertiesProvider(taskView.args), Path.class);
        parallelism = propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(Runtime.getRuntime().availableProcessors());
        indexTimeout = getIndexTimeout();
        this.progressCallback = progressCallback;

        Options<String> allTaskOptions = options().createFrom(Options.from(taskView.args));
        ((ElasticsearchSpewer) spewer.configure(allTaskOptions)).createIndexIfNotExists();

        DocumentFactory documentFactory = new DocumentFactory().configure(allTaskOptions);
        Extractor extractor = new Extractor(documentFactory).configure(allTaskOptions);

        consumer = new DocumentConsumer(spewer, extractor, this.parallelism);
        progressTrackConsumer = path -> {
            consumer.accept(path);
            processed.incrementAndGet();
            if (progressCallback != null) {
                progressCallback.apply(getProgressRate());
            }
        };
        if (propertiesProvider.getProperties().get(REPORT_NAME_OPT) != null) {
            logger.info("report map enabled with name set to {}", propertiesProvider.getProperties().get(REPORT_NAME_OPT));
            consumer.setReporter(new Reporter(factory.createMap(propertiesProvider.getProperties().get(REPORT_NAME_OPT).toString())));
        }
        drainer = new DocumentQueueDrainer<>(inputQueue, progressTrackConsumer).configure(allTaskOptions);
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("Processing up to {} file(s) in parallel", parallelism);
        totalToProcess = drainer.drain(PATH_POISON).get();
        drainer.shutdown();
        drainer.awaitTermination(10, SECONDS); // drain is finished
        logger.info("drained {} documents. Waiting for consumer to shutdown", totalToProcess);

        consumer.shutdown();
        // documents could be currently processed
        while (!consumer.awaitTermination(indexTimeout, MINUTES)) {
            logger.info("Consumer has not terminated yet.");
        }

        if (consumer.getReporter() != null) consumer.getReporter().close();
        logger.info("exiting");
        return totalToProcess;
    }

    /**
     * Retrieves the index timeout option in minutes based on app properties.
     *
     * @return The number of minutes.
     */
    private int getIndexTimeout() {
        return Integer.parseInt(propertiesProvider.get(INDEX_TIMEOUT_OPT).orElse(valueOf(DEFAULT_INDEX_TIMEOUT)));
    }


    @Override
    public double getProgressRate() {
        totalToProcess = max(inputQueue.size(), totalToProcess);
        return (double)(totalToProcess - inputQueue.size()) / totalToProcess;
    }
}
