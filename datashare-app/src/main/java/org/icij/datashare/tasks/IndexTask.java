package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.com.ShutdownMessage;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.extractor.DocumentConsumer;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.extractor.UpdatableDigester;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.DocumentQueueDrainer;
import org.icij.task.DefaultTask;
import org.icij.task.Option;
import org.icij.task.Options;
import org.icij.task.StringOptionParser;
import org.icij.task.annotation.OptionsClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Math.max;
import static java.lang.String.valueOf;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.com.Message.Field.VALUE;
import static org.icij.datashare.com.Message.Type.INIT_MONITORING;

@OptionsClass(Extractor.class)
@OptionsClass(DocumentQueueDrainer.class)
public class IndexTask extends DefaultTask<Long> implements Monitorable, UserTask {
    private static final String EXTRACT_DIGEST_METHOD = "idDigestMethod";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentQueueDrainer drainer;
    private final DocumentConsumer consumer;
    private final DocumentQueue queue;
    private final Publisher publisher;
    private final User user;
    private long totalToProcess;

    private Integer parallelism = Runtime.getRuntime().availableProcessors();

    @Inject
    public IndexTask(final ElasticsearchSpewer spewer, final Publisher publisher, @Assisted User user,
                     @Assisted final Options<String> userOptions) {
        this.user = user;
        userOptions.ifPresent("parallelism", o -> o.parse().asInteger()).ifPresent(this::setParallelism);
        this.publisher = publisher;
        String indexName = user.isNull() ? userOptions.valueIfPresent("defaultProject").orElse("local-datashare") : user.defaultProject();
        spewer.withIndex(indexName); // TODO: remove this
        spewer.createIndex();

        userOptions.add(new Option<>(EXTRACT_DIGEST_METHOD, StringOptionParser::new).update(Entity.HASHER.toString()));
        this.queue = new RedisUserDocumentQueue(user, userOptions);

        Options<String> allTaskOptions = options().createFrom(userOptions);
        Extractor extractor = new Extractor().configure(allTaskOptions);
        extractor.setDigester(new UpdatableDigester(indexName, Entity.HASHER.toString()));

        consumer = new DocumentConsumer(spewer, extractor, this.parallelism);
        drainer = new DocumentQueueDrainer(queue, consumer).configure(allTaskOptions);
    }

    @Override
    public Long call() throws Exception {
        logger.info("Processing up to {} file(s) in parallel", parallelism);
        totalToProcess = drainer.drain().get();
        drainer.shutdown();
        drainer.awaitTermination(10, SECONDS); // drain is finished
        logger.info("drained {} documents. Waiting for consumer to shutdown", totalToProcess);
        publisher.publish(Channel.NLP, new Message(INIT_MONITORING).add(VALUE, valueOf(totalToProcess)));
        consumer.shutdown();
        consumer.awaitTermination(30, MINUTES); // documents could be currently processed
        publisher.publish(Channel.NLP, new ShutdownMessage());
        queue.close();
        logger.info("exiting");
        return totalToProcess;
    }

    @Override
    public double getProgressRate() {
        totalToProcess = max(queue.size(), totalToProcess);
        return (double)(totalToProcess - queue.size()) / totalToProcess;
    }

    private void setParallelism(Integer integer) { this.parallelism = integer;}

    @Override
    public User getUser() {
        return user;
    }
}
