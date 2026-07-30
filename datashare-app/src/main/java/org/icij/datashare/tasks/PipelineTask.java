package org.icij.datashare.tasks;

import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.extract.queue.DocumentQueue;
import org.icij.task.DefaultTask;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

public abstract class PipelineTask<T> extends DefaultTask<Long> implements UserTask, CancellableTask {
    /**
     * Task-arg key carrying the id of the task whose output feeds this stage's input queue. Set by
     * the launcher (CliApp.runPipeline, TaskResource), never by an operator: it is not a CLI option.
     */
    public static final String UPSTREAM_TASK_ID = "upstreamTaskId";
    /**
     * How long a drain waits before re-polling a queue whose producer is still running. Fixed, not
     * an option: it paces an internal wait, and reading pollingInterval made stage handoff up to a
     * minute slow (that option's CLI default) while muddying what that option means.
     */
    protected static final long UPSTREAM_POLL_INTERVAL_MS = 1000;

    protected final DocumentQueue<T> inputQueue;
    protected final DocumentQueue<T> outputQueue;
    protected final Stage stage;
    protected final User user;
    protected final PropertiesProvider propertiesProvider;
    private final DocumentCollectionFactory<T> factory;
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
        return warnIfNull(indexer.get(projectName, ref.id(), ref.routing()), projectName, ref.id());
    }

    protected Document getDocument(Indexer indexer, String projectName, DocReference ref, List<String> sourceExcludes) {
        return warnIfNull(indexer.get(projectName, ref.id(), ref.routing(), sourceExcludes), projectName, ref.id());
    }

    /**
     * True when this throwable is, or wraps, an InterruptedException. Redisson and the
     * Elasticsearch rest-client both re-interrupt the thread and rethrow a RuntimeException
     * wrapping the InterruptedException, so a plain instanceof test misses a real cancellation.
     */
    protected static boolean causedByInterrupt(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    /**
     * The id of the task feeding this stage's input queue, or empty when this stage runs standalone
     * against an already filled queue. Read from the task args, which is where the launcher puts it.
     */
    protected Optional<String> upstreamTaskId() {
        return propertiesProvider.get(UPSTREAM_TASK_ID);
    }

    /**
     * True while the producer feeding this stage may still enqueue. An absent upstream id or an
     * unknown task means "no upstream to wait for", so the consumer falls back to exiting on an
     * empty queue, which is the pre-gate behaviour. A repository read failure says nothing about
     * the producer, so it counts as still running: retrying costs a poll interval, whereas calling
     * it finished can end the drain on a transient error and strand whatever is enqueued next.
     * JooqTaskRepository throws jOOQ's unchecked DataAccessException, hence the RuntimeException.
     */
    protected boolean upstreamRunning(TaskRepository taskRepository) {
        return upstreamTaskId().map(upstreamTaskId -> {
            try {
                return !taskRepository.getTask(upstreamTaskId).getState().isFinal();
            } catch (UnknownTask e) {
                LoggerFactory.getLogger(getClass()).warn("upstream task {} is unknown, treating it as finished", upstreamTaskId, e);
                return false;
            } catch (IOException | RuntimeException e) {
                LoggerFactory.getLogger(getClass()).warn("cannot read upstream task {} state, treating it as still running", upstreamTaskId, e);
                return true;
            }
        }).orElse(false);
    }

    /**
     * True when a drain that just polled an empty queue may stop: the producer feeding it is
     * terminal and the queue is still empty. The state read comes first on purpose, so an entry
     * enqueued between the caller's poll and that read is caught by the emptiness check instead of
     * being stranded. With no upstream id this is just "the queue is empty", which is the pre-gate
     * behaviour: stop on the first empty poll.
     */
    protected boolean drained(TaskRepository taskRepository) {
        return !upstreamRunning(taskRepository) && inputQueue.isEmpty();
    }

    // Transitional. Redis queue keys survive upgrades, so a pre-21.16 run can leave a "POISON"
    // entry in a String queue. Skip it instead of resolving it as a doc reference.
    protected boolean isLegacySentinel(String queueEntry) {
        if (!"POISON".equals(queueEntry)) {
            return false;
        }
        LoggerFactory.getLogger(getClass()).warn("skipping legacy POISON sentinel in queue {}", inputQueue.getName());
        return true;
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
