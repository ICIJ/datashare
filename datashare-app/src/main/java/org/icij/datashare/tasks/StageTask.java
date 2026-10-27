package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;

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

    public StageTask(Stage stage, User user, DocumentCollectionFactory<I> factory,
                     PropertiesProvider propertiesProvider, Class<I> clazz, UpstreamGate gate, StageRegistry registry) {
        super(stage, user, factory, propertiesProvider, clazz, gate);
        processor = registry.get(stage);
        propertiesProvider.getProperties().forEach((key, value) -> options.put(key.toString(), value));
        project = Project.project(propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("running stage {} for project {} from queue {} with {} worker(s)", stage, project.name,
                    inputQueue.getName(), nbWorkers);
        AtomicReference<StageResult> total = new AtomicReference<>(StageResult.EMPTY);
        StageContext<O> ctx = new StageContext<>() {
            public Project project() {return project;}

            public Map<String, Object> options() {return options;}

            public boolean isCancelled() {return Thread.currentThread().isInterrupted();}

            public void emit(List<O> out) {
                if (!out.isEmpty()) {
                    throw new UnsupportedOperationException("emit is not wired to the next stage's queue yet");
                }
            }
        };
        runWorkers(() -> drainQueue(PipelineTask::causedByInterrupt,
                                    entry -> total.accumulateAndGet(processor.process(List.of(entry), ctx),
                                                                    StageResult::plus)));
        StageResult result = total.get();
        logSummary(result);
        return result.updated() + result.unchanged();
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
