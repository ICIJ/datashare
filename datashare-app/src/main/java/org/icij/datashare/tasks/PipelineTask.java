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
    protected final DocumentQueue<T> inputQueue;
    protected final DocumentQueue<T> outputQueue;
    protected final Stage stage;
    protected final User user;
    protected final PropertiesProvider propertiesProvider;
    private final DocumentCollectionFactory<T> factory;
    public static Path PATH_POISON = Paths.get("POISON");
    public static String STRING_POISON = "POISON";


    public PipelineTask(Stage stage, User user, DocumentCollectionFactory<T> factory, final PropertiesProvider propertiesProvider, Class<T> clazz) {
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
        this.factory = factory;
        this.inputQueue = getInputQueue(clazz);
        this.outputQueue = getOutputQueue(clazz);
    }

    @Override
    public User getUser() { return user;}

    protected DocumentQueue<T> getInputQueue(Class<T> clazz) {
        String queueName = getInputQueueName();
        if (queueName != null) {
            return factory.createQueue(queueName, clazz);
        }
        return null;
    }

    protected DocumentQueue<T> getOutputQueue(Class<T> clazz) {
        String queueName = getOutputQueueName();
        if (queueName != null) {
            return factory.createQueue(queueName, clazz);
        }
        return null;
    }

    protected String getInputQueueName() {
        return new PipelineHelper(propertiesProvider).getQueueNameFor(stage);
    }

    protected String getOutputQueueName() {
        return new PipelineHelper(propertiesProvider).getOutputQueueNameFor(stage);
    }
}
