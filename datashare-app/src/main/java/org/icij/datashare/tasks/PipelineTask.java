package org.icij.datashare.tasks;

import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.queue.DocumentQueue;
import org.icij.task.DefaultTask;

import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class PipelineTask<T> extends DefaultTask<Long> implements UserTask {
    protected final DocumentQueue<T> queue;
    protected final Stage stage;
    protected final User user;
    protected final PropertiesProvider propertiesProvider;
    public static Path PATH_POISON = Paths.get("POISON");
    public static String STRING_POISON = "POISON";

    public PipelineTask(Stage stage, User user, String queueName, DocumentCollectionFactory<T> factory, final PropertiesProvider propertiesProvider, Class<T> clazz) {
        this.queue = factory.createQueue(queueName, clazz);
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
    }

    public PipelineTask(Stage stage, User user, DocumentCollectionFactory<T> factory, final PropertiesProvider propertiesProvider, Class<T> clazz) {
        this(stage, user, new PipelineHelper(propertiesProvider).getQueueNameFor(stage), factory, propertiesProvider, clazz);
    }

    @Override
    public User getUser() { return user;}

    protected String getOutputQueueName() {
        return new PipelineHelper(propertiesProvider).getOutputQueueNameFor(stage);
    }
}
