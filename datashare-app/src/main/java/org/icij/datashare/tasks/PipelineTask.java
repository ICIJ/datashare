package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.queue.DocumentQueue;
import org.icij.task.DefaultTask;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;

public abstract class PipelineTask extends DefaultTask<Long> implements UserTask {
    private final DatashareCli.Stage stage;
    protected final RedisUserDocumentQueue queue;
    protected final User user;
    private final PropertiesProvider propertiesProvider;
    public static Path POISON = Paths.get("POISON");

    public PipelineTask(DatashareCli.Stage stage, User user, String queueName, final PropertiesProvider propertiesProvider) {
        this.queue = new RedisUserDocumentQueue(queueName, propertiesProvider);
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
    }

    public PipelineTask(DatashareCli.Stage stage, User user, final PropertiesProvider propertiesProvider) {
        this.queue = new RedisUserDocumentQueue(propertiesProvider);
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
    }

    protected long transferToOutputQueue() {
        long transferred = 0L;
        while (queue.size() > 0) {
            this.queue.pollLastAndOfferFirstTo(getOutputQueueName());
            transferred++;
        }
        return transferred;
    }

    protected long transferToOutputQueue(Predicate<Path> filter) throws Exception {
        long originalSize = queue.size();
        try (DocumentQueue outputQueue = new RedisUserDocumentQueue(getOutputQueueName(), propertiesProvider)) {
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
        return queue.getQueueName() + ':' + stage.name().toLowerCase();
    }

    @Override
    public User getUser() { return user;}
}
