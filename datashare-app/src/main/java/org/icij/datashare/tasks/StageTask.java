package org.icij.datashare.tasks;

import java.util.HashMap;
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
public class StageTask<I, O> extends PipelineTask<I> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final StageProcessor<I, O> processor;
    private final Map<String, Object> options = new HashMap<>();
    private final Project project;
    private final DocumentQueue<O> nextStageQueue;

    public StageTask(Stage stage, User user, DocumentCollectionFactory<I> factory,
                     PropertiesProvider propertiesProvider, Class<I> clazz, UpstreamGate gate, StageRegistry registry) {
        this(stage, user, factory, propertiesProvider, clazz, gate, registry, null, null);
    }

    public StageTask(Stage stage, User user, DocumentCollectionFactory<I> factory,
                     PropertiesProvider propertiesProvider, Class<I> clazz, UpstreamGate gate, StageRegistry registry,
                     DocumentCollectionFactory<O> outputFactory, Class<O> outputClass) {
        super(stage, user, factory, propertiesProvider, clazz, gate);
        processor = registry.get(stage);
        propertiesProvider.getProperties().forEach((key, value) -> options.put(key.toString(), value));
        project = Project.project(propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
        nextStageQueue = outputFactory == null ? null : outputFactory.createQueue(getOutputQueueName(), outputClass);
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("running stage {} for project {} from queue {} with {} worker(s)", stage, project.name,
                    inputQueue.getName(), nbWorkers);
        AtomicReference<StageResult> total = new AtomicReference<>(StageResult.EMPTY);
        try (ScopedStageContext<O> ctx = new ScopedStageContext<>() {
            public Project project() {return project;}

            public Map<String, Object> options() {return options;}

            public boolean isCancelled() {return Thread.currentThread().isInterrupted();}

            public void emit(List<O> out) {
                if (out.isEmpty()) {
                    return;
                }
                if (nextStageQueue == null) {
                    throw new IllegalStateException("stage " + stage + " emits but has no output queue");
                }
                out.forEach(StageTask.this::offerToNextStage);
            }
        }) {
            runWorkers(() -> drainQueue(PipelineTask::causedByInterrupt, entry -> {
                total.accumulateAndGet(processor.process(List.of(entry), ctx), StageResult::plus);
                onEntryProcessed();
            }));
        }
        StageResult result = total.get();
        logSummary(result);
        return result.updated() + result.unchanged();
    }

    protected void setTaskId(String taskId) {
        options.put(StageContext.TASK_ID_OPT, taskId);
    }

    protected void onEntryProcessed() {}

    private void offerToNextStage(O item) {
        if (!nextStageQueue.offer(item)) {
            logger.warn("cannot offer {} to queue {}", item, nextStageQueue.getName());
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
}
