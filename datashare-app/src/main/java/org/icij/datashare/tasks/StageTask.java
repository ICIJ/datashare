package org.icij.datashare.tasks;

import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.DefaultStageContext;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;
import static java.util.Optional.ofNullable;
import org.icij.datashare.cli.QueueType;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_QUEUE_TYPE;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.QUEUE_TYPE_OPT;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.icij.datashare.PropertiesProvider;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.ScopedStageContext;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the {@link StageProcessor} registered for a stage over the entries of its input queue: the queue executor's
 * side of the stage contract. It owns the drain loop, the workers and the cancellation; the processor owns the work.
 * Per-document failures are counted by the processor, an infrastructure failure fails the run (see
 * {@link WorkersFailed}).
 */
public class StageTask<I, O> extends DefaultTask<Long> implements UserTask, CancellableTask {
    /**
     * How long a drain waits before re-polling a queue whose producer is still running. Fixed, not
     * an option: it paces an internal wait, and reading pollingInterval made stage handoff up to a
     * minute slow (that option's CLI default) while muddying what that option means.
     */
    protected static final long UPSTREAM_POLL_INTERVAL_MS = 1000;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    protected final DocumentQueue<I> inputQueue;
    protected final Stage stage;
    protected final User user;
    private final String taskId;
    protected final PropertiesProvider propertiesProvider;
    protected final UpstreamGate gate;
    /** Workers a parallel consumer stage runs, from --parallelism. A source stage ignores it. */
    protected final int nbWorkers;
    protected final ExecutorService workerPool;
    private volatile Thread taskThread;
    private final StageProcessor<I, O> processor;
    private final Map<String, Object> options = new HashMap<>();
    private final Project project;
    private final DocumentQueue<O> nextStageQueue;
    private final boolean dropsEmits;

    public StageTask(Stage stage, User user, String taskId, DocumentCollectionFactory<I> factory,
                     PropertiesProvider propertiesProvider, Class<I> clazz, UpstreamGate gate, StageRegistry registry) {
        this(stage, user, taskId, factory, propertiesProvider, clazz, gate, registry, null, null);
    }

    public StageTask(Stage stage, User user, String taskId, DocumentCollectionFactory<I> factory,
                     PropertiesProvider propertiesProvider, Class<I> clazz, UpstreamGate gate, StageRegistry registry,
                     DocumentCollectionFactory<O> outputFactory, Class<O> outputClass) {
        this.propertiesProvider = propertiesProvider;
        this.stage = stage;
        this.user = user;
        this.taskId = Objects.requireNonNull(taskId, "a stage task needs the id it is tracked under");
        this.gate = gate;
        // a source stage has no input queue name, and no input factory
        String inputQueueName = getInputQueueName();
        this.inputQueue = inputQueueName == null ? null : factory.createQueue(inputQueueName, clazz);
        this.nbWorkers = Math.max(1, propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(1));
        this.workerPool =
                Executors.newFixedThreadPool(nbWorkers, namedThreadFactory(stage.name().toLowerCase() + "-worker"));
        processor = registry.get(stage);
        propertiesProvider.getProperties().forEach((key, value) -> options.put(key.toString(), value));
        project = Project.project(propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
        String outputQueueName = getOutputQueueName();
        dropsEmits = nothingDrainsTheOutput(propertiesProvider, stage);
        nextStageQueue = outputFactory == null || outputQueueName == null || dropsEmits ? null :
                         outputFactory.createQueue(outputQueueName, outputClass);
    }

    /**
     * True when the stage is the last of the run and its output queue is in memory: nothing can ever drain it, so an
     * emit into it only fills it (a bounded queue then blocks the stage). A Redis or AMQP queue outlives the run and may
     * be drained by a later one, or on another machine, so it keeps being filled.
     */
    private static boolean nothingDrainsTheOutput(PropertiesProvider properties, Stage stage) {
        QueueType queueType =
                QueueType.valueOf(properties.get(QUEUE_TYPE_OPT).orElse(DEFAULT_QUEUE_TYPE.name()).toUpperCase());
        return queueType == QueueType.MEMORY && new PipelineHelper(properties).isLastStage(stage);
    }

    @Override
    public void cancel(boolean requeue) {
        // interrupt the task thread first: that is what makes the blocking wait on the workers in
        // runWorkers() throw InterruptedException and surface the cancellation. Then stop the pool
        // so the workers themselves wind down promptly.
        ofNullable(taskThread).ifPresent(Thread::interrupt);
        workerPool.shutdownNow();
    }

    @Override
    public User getUser() {
        return user;
    }

    @Override
    public Long call() throws Exception {
        taskThread = Thread.currentThread();
        StageResult result;
        try (ScopedStageContext<O> ctx = newContext()) {
            result = run(ctx);
        }
        logSummary(result);
        return result.updated() + result.unchanged();
    }

    private StageResult run(StageContext<O> ctx) throws InterruptedException {
        if (processor.itemType() == Void.class) {
            logger.info("running source stage {} for project {}", stage, project.name);
            StageResult result = processor.process(List.of(), ctx);
            if (Thread.interrupted()) {
                throw new InterruptedException("cancelled while running " + stage);
            }
            return result;
        }
        logger.info("running stage {} for project {} from queue {} with {} worker(s)", stage, project.name,
                    inputQueue.getName(), nbWorkers);
        AtomicReference<StageResult> total = new AtomicReference<>(StageResult.EMPTY);
        runWorkers(() -> drainQueue(t -> workerPool.isShutdown() && causedByInterrupt(t), entry -> {
            total.accumulateAndGet(processor.process(List.of(entry), ctx), StageResult::plus);
            onEntryProcessed();
        }));
        return total.get();
    }

    private ScopedStageContext<O> newContext() {
        return new DefaultStageContext<>(taskId, project, options, this::emitToNextStage);
    }

    private void emitToNextStage(List<O> out) {
        if (out.isEmpty() || dropsEmits) {
            return;
        }
        if (nextStageQueue == null) {
            throw new IllegalStateException("stage " + stage + " emits but has no output queue");
        }
        for (O item : out) {
            if (!putInNextStage(item)) {
                return;
            }
        }
    }

    protected void onEntryProcessed() {}

    /**
     * Waits for room in the queue of the next stage: a bounded queue that is full slows the emitter down, where an
     * offer would drop the item. False when the wait was interrupted (a cancellation): the flag is set back, and
     * what is left is not emitted.
     */
    private boolean putInNextStage(O item) {
        try {
            nextStageQueue.put(item);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void logSummary(StageResult result) {
        logger.info("stage {} done: {} updated, {} unchanged, {} skipped, {} failed", stage, result.updated(),
                    result.unchanged(), result.skipped(), result.failed());
        // A failed or skipped document keeps what it had, since nothing was written for it, so a plain re-run
        // redoes exactly those. Deliberate: one unreadable document does not fail a corpus-wide run, only a
        // dead worker does (see WorkersFailed).
        logRerunNeeded(result.skipped(), "could not be processed and were left as they were");
        logRerunNeeded(result.failed(), "failed");
    }

    private void logRerunNeeded(long nbDocuments, String reason) {
        if (nbDocuments > 0) {
            logger.error("{} document(s) {} in project {}, re-run the {} stage for them", nbDocuments, reason,
                         project.name, stage);
        }
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

    /**
     * Polls the input queue until it is {@link #drained()} or this thread is interrupted, handing
     * every entry to {@code onEntry}. Shared by the parallel consumer stages so cancellation,
     * and waiting on the upstream producer cannot drift between them.
     * {@code isCancellation} tells a cancelled poll from a broken queue client: a broken client is
     * an infrastructure failure and is rethrown, which is what makes the run fail instead of green.
     * {@code onEntry} owns what a single document going wrong means, and ends the drain by
     * re-interrupting this thread.
     */
    protected void drainQueue(Predicate<Throwable> isCancellation, Consumer<I> onEntry) {
        // The interrupt check keeps cancellation prompt, since cancel() calls executor.shutdownNow()
        // while a worker may sit between two non-blocking polls.
        while (!Thread.currentThread().isInterrupted()) {
            I queueEntry = pollOrStop(isCancellation);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (queueEntry == null) {
                if (drained() || !waitForUpstream()) {
                    return;
                }
                continue;
            }
            onEntry.accept(queueEntry);
        }
    }

    /** The next entry, null when the queue is empty, and null with this thread re-interrupted when cancelled. */
    private I pollOrStop(Predicate<Throwable> isCancellation) {
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
     * Runs {@link #nbWorkers} copies of {@code worker} on the stage's pool and waits for all of
     * them, failing the run with {@link WorkersFailed} if any died. The pool is shut down on every
     * path: normal completion, worker failure, and cancellation (where the InterruptedException
     * propagates out and TaskWorkerLoop records the run as cancelled). No awaitTermination():
     * waiting would only delay cancellation, and workers leave nothing behind.
     */
    protected void runWorkers(Runnable worker) throws InterruptedException {
        try {
            awaitWorkers(IntStream.range(0, nbWorkers).<Future<?>>mapToObj(i -> workerPool.submit(worker)).toList());
        } finally {
            workerPool.shutdownNow();
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

    private static ThreadFactory namedThreadFactory(String prefix) {
        return Thread.ofPlatform().name(prefix + "-", 1).factory();
    }

    protected String getInputQueueName() {
        return new PipelineHelper(propertiesProvider).getQueueNameFor(stage);
    }

    protected String getOutputQueueName() {
        return new PipelineHelper(propertiesProvider).getOutputQueueNameFor(stage);
    }
}
