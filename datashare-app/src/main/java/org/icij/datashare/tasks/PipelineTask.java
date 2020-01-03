package org.icij.datashare.tasks;

import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.queue.DocumentQueue;
import org.icij.task.DefaultTask;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;

import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPTION;

public abstract class PipelineTask extends DefaultTask<Long> implements UserTask {
    private final DatashareCli.Stage stage;
    protected final DocumentQueue queue;
    protected final User user;
    private final PropertiesProvider propertiesProvider;
    public static Path POISON = Paths.get("POISON");
    private final DocumentCollectionFactory factory;

    public PipelineTask(DatashareCli.Stage stage, User user, String queueName, DocumentCollectionFactory factory, final PropertiesProvider propertiesProvider) {
        this.factory = factory;
        this.queue = factory.createQueue(propertiesProvider, queueName);
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
    }

    public PipelineTask(DatashareCli.Stage stage, User user, DocumentCollectionFactory factory, final PropertiesProvider propertiesProvider) {
        this(stage, user, propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), factory, propertiesProvider);
    }

    protected long transferToOutputQueue() throws Exception {
        try (DocumentQueue outputQueue = factory.createQueue(propertiesProvider, getOutputQueueName())) {
            return this.queue.drainTo(outputQueue);
        }
    }

    protected long transferToOutputQueue(Predicate<Path> filter) throws Exception {
        long originalSize = queue.size();
        try (DocumentQueue outputQueue = factory.createQueue(propertiesProvider, getOutputQueueName())) {
            Path path;
            while (!(path = queue.take()).equals(POISON)) {
                if (filter.test(path)) {
                    outputQueue.add(path);
                }
            }
            outputQueue.add(POISON);
            return originalSize - outputQueue.size();
        }
    }

    public String getOutputQueueName() {
        return PipelineHelper.getQueueName(propertiesProvider, stage);
    }

    @Override
    public User getUser() { return user;}
}
