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
    public static Path PATH_POISON = Paths.get("POISON");
    public static String STRING_POISON = "POISON";

    public PipelineTask(DatashareCli.Stage stage, User user, String queueName, DocumentCollectionFactory<T> factory, final PropertiesProvider propertiesProvider, Class<T> clazz) {
        this.queue = factory.createQueue(propertiesProvider, queueName, clazz);
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
    }

    public PipelineTask(DatashareCli.Stage stage, User user, DocumentCollectionFactory<T> factory, final PropertiesProvider propertiesProvider, Class<T> clazz) {
        this(stage, user, propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), factory, propertiesProvider, clazz);
    }

    public String getOutputQueueName() {
        return PipelineHelper.getQueueName(propertiesProvider, stage);
    }

    @Override
    public User getUser() { return user;}
}
