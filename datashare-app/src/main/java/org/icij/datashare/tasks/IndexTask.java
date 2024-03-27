package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.DocumentConsumer;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueueDrainer;
import org.icij.extract.report.Reporter;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiFunction;

import static java.lang.Math.max;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.REPORT_NAME_OPT;

@OptionsClass(Extractor.class)
@OptionsClass(DocumentFactory.class)
@OptionsClass(DocumentQueueDrainer.class)
public class IndexTask extends PipelineTask<Path> implements Monitorable{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentQueueDrainer<Path> drainer;
    private final DocumentConsumer consumer;
    private long totalToProcess;
    private final Integer parallelism;

    @Inject
    public IndexTask(final ElasticsearchSpewer spewer, final DocumentCollectionFactory<Path> factory, @Assisted TaskView<Long> taskView, @Assisted final BiFunction<String, Double, Void> updateCallback) throws IOException {
        super(Stage.INDEX, taskView.user, factory, new PropertiesProvider(taskView.properties), Path.class);
        parallelism = propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(Runtime.getRuntime().availableProcessors());

        Options<String> allTaskOptions = options().createFrom(Options.from(taskView.properties));
        ((ElasticsearchSpewer) spewer.configure(allTaskOptions)).createIndexIfNotExists();

        DocumentFactory documentFactory = new DocumentFactory().configure(allTaskOptions);
        Extractor extractor = new Extractor(documentFactory).configure(allTaskOptions);

        consumer = new DocumentConsumer(spewer, extractor, this.parallelism);
        if (propertiesProvider.getProperties().get(REPORT_NAME_OPT) != null) {
            logger.info("report map enabled with name set to {}", propertiesProvider.getProperties().get(REPORT_NAME_OPT));
            consumer.setReporter(new Reporter(factory.createMap(propertiesProvider.getProperties().get(REPORT_NAME_OPT).toString())));
        }
        drainer = new DocumentQueueDrainer<>(inputQueue, consumer).configure(allTaskOptions);
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
        while (!consumer.awaitTermination(30, MINUTES)) {
            logger.info("Consumer has not terminated yet.");
        }

        if (consumer.getReporter() != null) consumer.getReporter().close();
        logger.info("exiting");
        return totalToProcess;
    }

    @Override
    public double getProgressRate() {
        totalToProcess = max(inputQueue.size(), totalToProcess);
        return (double)(totalToProcess - inputQueue.size()) / totalToProcess;
    }
}
