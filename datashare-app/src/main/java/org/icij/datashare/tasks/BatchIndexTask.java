package org.icij.datashare.tasks;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.ConductorTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
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

@ConductorTask(name = "BatchIndexTask")
@OptionsClass(Extractor.class)
@OptionsClass(DocumentFactory.class)
@OptionsClass(DocumentQueueDrainer.class)
@Option(name = DEFAULT_PROJECT_OPT, description = "the default project name")
@Option(name = "projectName", description = "task project name")
@TaskGroup(TaskGroupType.Java)
public class BatchIndexTask extends DefaultTask<Long> implements UserTask, CancellableTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DocumentConsumer consumer;
    private final BatchHandler<String> batchHandler;
    private final String batchId;
    private final Integer parallelism;
    private final User user;
    private volatile Thread taskThread;

    @Inject
    public BatchIndexTask(
        final ElasticsearchSpewer spewer,
        BatchHandler<String> batchHandler,
        final PropertiesProvider propertiesProvider,
        @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progress
    ) throws IOException {
        super();
        this.batchHandler = batchHandler;
        this.user = taskView.getUser();
        parallelism = propertiesProvider
            .get(PARALLELISM_OPT)
            .map(Integer::parseInt)
            .orElse(Runtime.getRuntime().availableProcessors());
        Options<String> allTaskOptions = options()
            .createFrom(Options.from(PropertiesProvider.ofNullable(taskView.args)
                .getProperties()));
        ((ElasticsearchSpewer) spewer.configure(allTaskOptions)).createIndexIfNotExists();

        DocumentFactory documentFactory = new DocumentFactory().configure(allTaskOptions);
        Extractor extractor = new Extractor(documentFactory).configure(allTaskOptions);

        this.batchId = (String) Optional.ofNullable(taskView.args.get("batchId"))
            .orElseThrow(() -> new NullPointerException("missing batchId task args"));

        consumer = new DocumentConsumer(spewer, extractor, this.parallelism);
    }

    @Override
    public Long call() throws Exception {
        taskThread = Thread.currentThread();
        List<Path> filePaths = (List<Path>) batchHandler.getBatch(batchId);
        logger.info("Processing up to {} file(s) in parallel", parallelism);
        filePaths.forEach(consumer);
        logger.info("drained {} documents. Waiting for consumer to shutdown", filePaths.size());
        consumer.shutdown();
        // documents could be currently processed
        while (!consumer.awaitTermination(30, MINUTES)) {
            logger.info("Consumer has not terminated yet.");
        }
        logger.info("exiting");
        return (long) filePaths.size();
    }

    @Override
    public void cancel(boolean requeue) {
        ofNullable(taskThread).ifPresent(Thread::interrupt);
    }

    @Override
    public User getUser() {
        return user;
    }
}
