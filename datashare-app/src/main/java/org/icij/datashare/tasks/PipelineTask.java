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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import static java.util.Optional.ofNullable;

public abstract class PipelineTask<T> extends DefaultTask<Long> implements UserTask, CancellableTask {
    /**
     * How long a drain waits before re-polling a queue whose producer is still running. Fixed, not
     * an option: it paces an internal wait, and reading pollingInterval made stage handoff up to a
     * minute slow (that option's CLI default) while muddying what that option means.
     */
    protected static final long UPSTREAM_POLL_INTERVAL_MS = 1000;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    protected final DocumentQueue<T> inputQueue;
    protected final DocumentQueue<T> outputQueue;
    protected final Stage stage;
    protected final User user;
    protected final PropertiesProvider propertiesProvider;
    protected final UpstreamGate gate;
    private final DocumentCollectionFactory<T> factory;
    private volatile Thread taskThread;

    /** For producer stages: nothing feeds their input queue, the gate is {@link UpstreamGate#NONE}. */
    public PipelineTask(Stage stage, User user, DocumentCollectionFactory<T> factory,
                        final PropertiesProvider propertiesProvider, Class<T> clazz) {
        this(stage, user, factory, propertiesProvider, clazz, UpstreamGate.NONE);
    }

    public PipelineTask(Stage stage, User user, DocumentCollectionFactory<T> factory,
                        final PropertiesProvider propertiesProvider, Class<T> clazz, UpstreamGate gate) {
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
        this.factory = factory;
        this.gate = gate;
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
    public User getUser() {
        return user;
    }

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
     * True when a drain that just polled an empty queue may stop: the producer feeding it is done
     * and the queue is still empty. The gate read comes first on purpose, so an entry enqueued
     * between the caller's poll and that read is caught by the emptiness check instead of being
     * stranded. With no upstream this is just "the queue is empty", the pre-gate behaviour: stop
     * on the first empty poll.
     */
    protected boolean drained() {
        return !gate.mayGrow() && inputQueue.isEmpty();
    }

    // Transitional. Redis queue keys survive upgrades, so a pre-21.16 run can leave a "POISON"
    // entry in a String queue. Callers skip it instead of resolving it as a doc reference.
    protected static boolean isLegacySentinel(Object queueEntry) {
        return "POISON".equals(queueEntry);
    }

    /**
     * Polls the input queue until it is {@link #drained()} or this thread is interrupted, handing
     * every entry to {@code onEntry}. Shared by the parallel consumer stages so cancellation,
     * waiting on the upstream producer and legacy sentinels cannot drift between them.
     * {@code isCancellation} tells a cancelled poll from a broken queue client: a broken client is
     * an infrastructure failure and is rethrown, which is what makes the run fail instead of green.
     * {@code onEntry} owns what a single document going wrong means, and ends the drain by
     * re-interrupting this thread.
     */
    protected void drainQueue(Predicate<Throwable> isCancellation, Consumer<T> onEntry) {
        // The interrupt check keeps cancellation prompt, since cancel() calls executor.shutdownNow()
        // while a worker may sit between two non-blocking polls.
        while (!Thread.currentThread().isInterrupted()) {
            T queueEntry = pollOrStop(isCancellation);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (queueEntry == null) {
                if (drained() || !waitForUpstream()) {
                    return;
                }
                continue;
            }
            if (isLegacySentinel(queueEntry)) {
                logger.warn("skipping legacy POISON sentinel in queue {}", inputQueue.getName());
                continue;
            }
            onEntry.accept(queueEntry);
        }
    }

    /** The next entry, null when the queue is empty, and null with this thread re-interrupted when cancelled. */
    private T pollOrStop(Predicate<Throwable> isCancellation) {
        try {
            return inputQueue.poll();
        } catch (RuntimeException e) {
            if (!isCancellation.test(e)) {
                throw e;
            }
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** False when the wait was cancelled: a Runnable cannot throw it, so the flag is set back instead. */
    private boolean waitForUpstream() {
        try {
            Thread.sleep(UPSTREAM_POLL_INTERVAL_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Runs {@code parallelism} copies of {@code worker} on {@code executor} and waits for all of
     * them, failing the run with {@link WorkersFailed} if any died. The executor is shut down on
     * every path: normal completion, worker failure, and cancellation (where the
     * InterruptedException propagates out and TaskWorkerLoop records the run as cancelled). No
     * awaitTermination(): waiting would only delay cancellation, and workers leave nothing behind.
     */
    protected void runWorkers(ExecutorService executor, int parallelism, Runnable worker) throws InterruptedException {
        try {
            awaitWorkers(IntStream.range(0, parallelism).<Future<?>>mapToObj(i -> executor.submit(worker)).toList());
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitWorkers(List<Future<?>> workers) throws InterruptedException {
        List<Throwable> failures = workers.stream().map(this::awaitWorker).filter(Objects::nonNull).toList();
        // Thread.interrupted() tests AND clears, so the cancellation flag does not leak onto the
        // runner thread alongside the InterruptedException. It is also the only signal left when
        // every worker had already completed by the time cancel() landed.
        if (Thread.interrupted()) {
            throw new InterruptedException("cancelled while draining " + inputQueue.getName());
        }
        if (!failures.isEmpty()) {
            throw new WorkersFailed(stage, failures.size(), workers.size(), failures.get(0));
        }
    }

    /** The cause of this worker's abnormal termination, or null when it ended normally. */
    private Throwable awaitWorker(Future<?> worker) {
        try {
            worker.get();
            return null;
        } catch (ExecutionException e) {
            logger.error("{} worker terminated abnormally", stage, e.getCause());
            return e.getCause();
        } catch (InterruptedException e) {
            // cancelled mid-wait: re-interrupt so awaitWorkers reports it once, for the whole pool
            Thread.currentThread().interrupt();
            return null;
        }
    }

    protected static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + counter.incrementAndGet());
            return thread;
        };
    }

    private Document warnIfNull(Document document, String projectName, String docId) {
        // indexer.get() also returns null on fetch failures (it logs them as ERROR), not only on missing ids
        if (document == null) {
            logger.warn(
                    "document <{}> could not be retrieved from index {} (missing document or index fetch error), skipping",
                    docId, projectName);
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
