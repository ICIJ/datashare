package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;

public abstract class PipelineTask extends DefaultTask<Long> implements UserTask {
    private final DatashareCli.Stage stage;
    protected final RedisUserDocumentQueue queue;
    protected final User user;

    public PipelineTask(DatashareCli.Stage stage, User user, String queueName, final PropertiesProvider propertiesProvider) {
        this.queue = new RedisUserDocumentQueue(queueName, propertiesProvider);
        this.stage = stage;
        this.user = user;
    }

    public PipelineTask(DatashareCli.Stage stage, User user, final PropertiesProvider propertiesProvider) {
        this.queue = new RedisUserDocumentQueue(propertiesProvider);
        this.stage = stage;
        this.user = user;
    }

    protected void transferToOutputQueue() {
        int size = this.queue.size();
        for (int i = 0; i <= size; i++) {
            this.queue.pollLastAndOfferFirstTo(getOutputQueueName());
        }
    }

    public String getOutputQueueName() {
        return queue.getQueueName() + ':' + stage.name().toLowerCase();
    }

    @Override
    public User getUser() { return user;}
}
