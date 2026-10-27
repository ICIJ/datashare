package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.LanguageOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Re-detects the language of every document referenced in the LANGUAGE queue and rewrites the
 * language field in place, for corpora indexed by an older guesser. It detects on exactly what
 * index time detects on ({@link LanguageGuesser#guess(String, java.nio.file.Path, Object, int)}), file-name
 * fallback included, so a re-run cannot downgrade a document the INDEX stage got right.
 */
@TemporalSingleActivityWorkflow(name = "language", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class LanguageDetectTask extends PipelineTask<String> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final LanguageStage stage;
    private final Project project;

    @Inject
    public LanguageDetectTask(DocumentCollectionFactory<String> factory, Indexer indexer,
                              LanguageGuesser languageGuesser, final UpstreamGate.Factory gateFactory,
                              @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.LANGUAGE, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class,
              gateFactory.forTask(taskView));
        LanguageOptions options = propertiesProvider.toRecord(LanguageOptions.class);
        project = Project.project(options.defaultProject());
        stage = new LanguageStage(indexer, languageGuesser, options);
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("re-detecting document languages for project {} from queue {} with {} worker(s)", project.name,
                    inputQueue.getName(), nbWorkers);
        AtomicReference<StageResult> total = new AtomicReference<>(StageResult.EMPTY);
        StageContext<Void> ctx = new StageContext<>() {
            public Project project() { return project; }
            public Map<String, Object> options() { return Map.of(); }
            public boolean isCancelled() { return Thread.currentThread().isInterrupted(); }
            public void emit(List<Void> out) { /* LANGUAGE is a terminal stage: nothing flows downstream */ }
        };
        runWorkers(() -> drainQueue(PipelineTask::causedByInterrupt, entry -> total.accumulateAndGet(
                stage.process(List.of(entry), ctx), StageResult::plus)));
        StageResult result = total.get();
        logSummary(result);
        return result.updated() + result.unchanged();
    }

    private void logSummary(StageResult result) {
        logger.info("language re-detection done: {} updated, {} unchanged, {} skipped, {} failed",
                    result.updated(), result.unchanged(), result.skipped(), result.failed());
        logRerunNeeded(result.skipped(), "could not be retrieved from the index and kept their language");
        // A failed document keeps the language it had, since nothing was written for it, so a plain re-run
        // redoes exactly those. Deliberate: one unreadable document does not fail a corpus-wide run, only a
        // dead worker does (see WorkersFailed).
        logRerunNeeded(result.failed(), "failed language detection");
    }

    private void logRerunNeeded(long nbDocuments, String reason) {
        if (nbDocuments > 0) {
            logger.error("{} document(s) {} in project {}, re-run the LANGUAGE stage for them", nbDocuments, reason,
                         project.name);
        }
    }
}
