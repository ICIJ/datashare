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

import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPTION;

public abstract class PipelineTask<T> extends DefaultTask<Long> implements UserTask {
    private final DatashareCli.Stage stage;
    protected final DocumentQueue<T> queue;
    protected final User user;
    protected final PropertiesProvider propertiesProvider;
    public static Path POISON = Paths.get("POISON");

    public PipelineTask(DatashareCli.Stage stage, User user, String queueName, DocumentCollectionFactory factory, final PropertiesProvider propertiesProvider) {
        this.queue = factory.createQueue(propertiesProvider, queueName);
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
    }

    public PipelineTask(DatashareCli.Stage stage, User user, DocumentCollectionFactory factory, final PropertiesProvider propertiesProvider) {
        this(stage, user, propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), factory, propertiesProvider);
    }

    public String getOutputQueueName() {
        return PipelineHelper.getQueueName(propertiesProvider, stage);
    }

    @Override
    public User getUser() { return user;}
}
