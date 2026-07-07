package org.icij.datashare.tasks;

import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.queue.DocumentQueue;
import org.icij.task.DefaultTask;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static java.util.Optional.ofNullable;

public abstract class PipelineTask<T> extends DefaultTask<Long> implements UserTask, CancellableTask {
    protected final DocumentQueue<T> inputQueue;
    protected final DocumentQueue<T> outputQueue;
    protected final Stage stage;
    protected final User user;
    protected final PropertiesProvider propertiesProvider;
    private final DocumentCollectionFactory<T> factory;
    public static Path PATH_POISON = Paths.get("POISON");
    public static String STRING_POISON = "POISON";
    private volatile Thread taskThread;

    public PipelineTask(Stage stage, User user, DocumentCollectionFactory<T> factory, final PropertiesProvider propertiesProvider, Class<T> clazz) {
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
        this.factory = factory;
        this.inputQueue = getInputQueue(clazz);
        this.outputQueue = getOutputQueue(clazz);
    }

    @Override
    public void cancel(boolean requeue) {
        ofNullable(taskThread).ifPresent(Thread::interrupt);
    }

    public Long call() throws Exception {
        taskThread = Thread.currentThread();
        return 0L;
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

    protected Document getDocument(Indexer indexer, String projectName, DocReference ref) {
        return warnIfNull(indexer.get(projectName, ref.id(), ofNullable(ref.rootId()).orElse(ref.id())), projectName, ref.id());
    }

    protected Document getDocument(Indexer indexer, String projectName, DocReference ref, List<String> sourceExcludes) {
        return warnIfNull(indexer.get(projectName, ref.id(), ofNullable(ref.rootId()).orElse(ref.id()), sourceExcludes), projectName, ref.id());
    }

    private Document warnIfNull(Document document, String projectName, String docId) {
        // indexer.get() also returns null on fetch failures (it logs them as ERROR), not only on missing ids
        if (document == null) {
            LoggerFactory.getLogger(getClass()).warn("document <{}> could not be retrieved from index {} (missing document or index fetch error), skipping", docId, projectName);
        }
        return document;
    }

    protected String getInputQueueName() {
        return new PipelineHelper(propertiesProvider).getQueueNameFor(stage);
    }

    protected String getOutputQueueName() {
        return new PipelineHelper(propertiesProvider).getOutputQueueNameFor(stage);
    }
}
