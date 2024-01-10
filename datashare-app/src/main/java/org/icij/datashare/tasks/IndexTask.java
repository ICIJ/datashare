package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.ShutdownMessage;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.user.User;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.DocumentConsumer;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueueDrainer;
import org.icij.extract.report.Reporter;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Properties;

import static java.lang.Math.max;
import static java.lang.String.valueOf;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.PropertiesProvider.MAP_NAME_OPTION;
import static org.icij.datashare.com.Message.Field.VALUE;
import static org.icij.datashare.com.Message.Type.INIT_MONITORING;

@OptionsClass(Extractor.class)
@OptionsClass(DocumentFactory.class)
@OptionsClass(DocumentQueueDrainer.class)
public class IndexTask extends PipelineTask implements Monitorable{
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentQueueDrainer<Path> drainer;
    private final DocumentConsumer consumer;
    private final Publisher publisher;
    private long totalToProcess;
    private final Integer parallelism;

    @Inject
    public IndexTask(final ElasticsearchSpewer spewer, final Publisher publisher, final DocumentCollectionFactory factory, @Assisted User user, @Assisted String queueName,
                     @Assisted final Properties properties) {
        super(DatashareCli.Stage.INDEX, user, queueName, factory, new PropertiesProvider(properties));
        PropertiesProvider propertiesProvider = new PropertiesProvider(properties);
        parallelism = propertiesProvider.get("parallelism").map(Integer::parseInt).orElse(Runtime.getRuntime().availableProcessors());
        this.publisher = publisher;
        String indexName = propertiesProvider.get("defaultProject").orElse("local-datashare");
        spewer.withIndex(indexName); // TODO: remove this
        spewer.createIndex();

        Options<String> allTaskOptions = options().createFrom(Options.from(properties));
        DocumentFactory documentFactory = new DocumentFactory().configure(allTaskOptions);
        Extractor extractor = new Extractor(documentFactory).configure(allTaskOptions);

        consumer = new DocumentConsumer(spewer, extractor, this.parallelism);
        if (propertiesProvider.getProperties().get(MAP_NAME_OPTION) != null) {
            logger.info("report map enabled with name set to {}", propertiesProvider.getProperties().get(MAP_NAME_OPTION));
            consumer.setReporter(new Reporter(factory.createMap(propertiesProvider, propertiesProvider.getProperties().get(MAP_NAME_OPTION).toString())));
        }
        drainer = new DocumentQueueDrainer<>(queue, consumer).configure(allTaskOptions);
    }

    @Override
    public Long call() throws Exception {
        logger.info("Processing up to {} file(s) in parallel", parallelism);
        totalToProcess = drainer.drain(POISON).get();
        drainer.shutdown();
        drainer.awaitTermination(10, SECONDS); // drain is finished
        logger.info("drained {} documents. Waiting for consumer to shutdown", totalToProcess);
        publisher.publish(Channel.NLP, new Message(INIT_MONITORING).add(VALUE, valueOf(totalToProcess)));

        consumer.shutdown();
        // documents could be currently processed
        try {
            while (!consumer.awaitTermination(30, MINUTES)) {
                logger.info("Consumer has not terminated yet.");
            }
        } catch (InterruptedException iex) {
            logger.info("Got InterruptedException while waiting for the consumer shutdown.");
        }
        publisher.publish(Channel.NLP, new ShutdownMessage());

        if (consumer.getReporter() != null) consumer.getReporter().close();
        queue.close();
        logger.info("exiting");
        return totalToProcess;
    }

    @Override
    public double getProgressRate() {
        totalToProcess = max(queue.size(), totalToProcess);
        return (double)(totalToProcess - queue.size()) / totalToProcess;
    }
}
