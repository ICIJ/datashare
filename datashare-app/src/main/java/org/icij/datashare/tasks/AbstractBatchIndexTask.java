package org.icij.datashare.tasks;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.DocumentConsumer;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueueDrainer;
import org.icij.task.DefaultTask;
import org.icij.task.Options;
import org.icij.task.annotation.Option;
import org.icij.task.annotation.OptionsClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OptionsClass(Extractor.class)
@OptionsClass(DocumentFactory.class)
@OptionsClass(DocumentQueueDrainer.class)
@Option(name = DEFAULT_PROJECT_OPT, description = "the default project name")
@Option(name = "projectName", description = "task project name")
public abstract class AbstractBatchIndexTask extends DefaultTask<Long> implements UserTask, CancellableTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentConsumer consumer;
    protected final Task<?> task;
    private final Integer parallelism;
    private final User user;
    private volatile Thread taskThread;

    public AbstractBatchIndexTask(
        final ElasticsearchSpewer spewer, final PropertiesProvider propertiesProvider, Task<Long> taskView
    ) throws IOException {
        super();
        this.task = taskView;
        this.user = taskView.getUser();
        parallelism = propertiesProvider
            .get(PARALLELISM_OPT)
            .map(Integer::parseInt)
            .orElse(Runtime.getRuntime().availableProcessors());
        Options<String> allTaskOptions = options()
            .createFrom(Options.from(PropertiesProvider.ofNullable(taskView.args).getProperties()));
        spewer.configure(allTaskOptions);
        try {
            spewer.createIndexIfNotExists();
        } catch (ElasticsearchException ex) { // Potential concurrency issue
            if (!Optional.ofNullable(ex.error().type()).orElse("").equals("resource_already_exists_exception")) {
                throw ex;
            }
        }
        DocumentFactory documentFactory = new DocumentFactory().configure(allTaskOptions);
        Extractor extractor = new Extractor(documentFactory).configure(allTaskOptions);
        consumer = new DocumentConsumer(spewer, extractor, this.parallelism);
    }

    @Override
    public Long call() throws Exception {
        List<Path> paths = getFilePaths();
        taskThread = Thread.currentThread();
        logger.info("Processing up to {} file(s) in parallel", parallelism);
        paths.forEach(consumer);
        logger.info("drained {} documents. Waiting for consumer to shutdown", paths.size());
        consumer.shutdown();
        // documents could be currently processed
        while (!consumer.awaitTermination(30, MINUTES)) {
            logger.info("Consumer has not terminated yet.");
        }
        logger.info("exiting");
        return (long) paths.size();
    }

    abstract List<Path> getFilePaths() throws IOException;

    @Override
    public void cancel(boolean requeue) {
        ofNullable(taskThread).ifPresent(Thread::interrupt);
    }

    @Override
    public User getUser() {
        return user;
    }
}
