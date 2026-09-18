package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;

/**
 * Re-detects the language of every document referenced in the LANGUAGE queue and rewrites the
 * language field in place, for corpora indexed by an older guesser. It detects on exactly what
 * index time detects on ({@link LanguageGuesser#guess(String, java.nio.file.Path)}), file-name
 * fallback included, so a re-run cannot downgrade a document the INDEX stage got right.
 */
@TemporalSingleActivityWorkflow(name = "language", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class LanguageDetectTask extends PipelineTask<String> {
    private static final List<String> SOURCE_EXCLUDES = List.of("content_translated");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final LanguageGuesser languageGuesser;
    private final Project project;
    private final int parallelism;
    private final ExecutorService executor;

    @Inject
    public LanguageDetectTask(DocumentCollectionFactory<String> factory, Indexer indexer,
                              LanguageGuesser languageGuesser, final UpstreamGate.Factory gateFactory,
                              @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.LANGUAGE, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class,
              gateFactory.forTask(taskView));
        this.indexer = indexer;
        this.languageGuesser = languageGuesser;
        project = Project.project((String) taskView.args.getOrDefault(DEFAULT_PROJECT_OPT, DEFAULT_DEFAULT_PROJECT));
        parallelism = Math.max(1, propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(1));
        executor = Executors.newFixedThreadPool(parallelism, namedThreadFactory("language-worker"));
    }

    @Override
    public void cancel(boolean requeue) {
        // interrupt the task thread first (PipelineTask): that is what makes the blocking wait on the
        // workers in call() throw InterruptedException and surface the cancellation. Then stop the
        // worker pool so the workers themselves wind down promptly.
        super.cancel(requeue);
        executor.shutdownNow();
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("re-detecting document languages for project {} from queue {} with {} worker(s)", project.name,
                    inputQueue.getName(), parallelism);
        DetectionCounters counters = new DetectionCounters();
        runWorkers(executor, parallelism,
                   () -> drainQueue(PipelineTask::causedByInterrupt, entry -> detectLanguage(entry, counters)));
        logSummary(counters);
        return counters.nbProcessed();
    }

    private void detectLanguage(String queueEntry, DetectionCounters counters) {
        try {
            detectAndUpdate(queueEntry, counters);
        } catch (IOException | RuntimeException e) {
            recordFailure(queueEntry, counters, e);
        }
    }

    private void detectAndUpdate(String queueEntry, DetectionCounters counters) throws IOException {
        DocReference ref = DocReference.parse(queueEntry);
        Document document = getDocument(indexer, project.name, ref, SOURCE_EXCLUDES);
        if (document == null) {
            counters.skipped();
            return;
        }
        updateLanguage(document, ref, counters);
    }

    /** A cancelled worker ends its drain; anything else is one document going wrong, counted and re-runnable. */
    private void recordFailure(String queueEntry, DetectionCounters counters, Throwable failure) {
        if (causedByInterrupt(failure)) {
            Thread.currentThread().interrupt();
            return;
        }
        logger.error("language detection failed for queue entry <{}>", queueEntry, failure);
        counters.failed();
    }

    private void updateLanguage(Document document, DocReference ref, DetectionCounters counters) throws IOException {
        Language guess = languageGuesser.guess(document.getContent(), document.getPath());
        if (guess == document.getLanguage()) {
            counters.unchanged();
            return;
        }
        indexer.update(project.name, document.getId(), Map.of("language", guess.name()), ref.routing());
        counters.updated();
    }

    private void logSummary(DetectionCounters counters) {
        logger.info("language re-detection done: {} updated, {} unchanged, {} skipped, {} failed", counters.nbUpdated(),
                    counters.nbUnchanged(), counters.nbSkipped(), counters.nbFailed());
        logSkipped(counters.nbSkipped());
        logFailed(counters.nbFailed());
    }

    private void logSkipped(long nbSkipped) {
        if (nbSkipped > 0) {
            logger.error("{} document(s) could not be retrieved from index {} and kept their language, re-run the " +
                         "LANGUAGE stage for them", nbSkipped, project.name);
        }
    }

    // A failed document keeps the language it had, since nothing was written for it, so a plain re-run
    // redoes exactly those. Deliberate: one unreadable document does not fail a corpus-wide run, only a
    // dead worker does (see WorkersFailed).
    private void logFailed(long nbFailed) {
        if (nbFailed > 0) {
            logger.error("{} document(s) failed language detection in project {}, re-run the LANGUAGE stage for them",
                         nbFailed, project.name);
        }
    }

    /** The four tallies of a run: they are written by every worker and read together, so they travel as one. */
    private static class DetectionCounters {
        private final AtomicLong nbUpdated = new AtomicLong();
        private final AtomicLong nbUnchanged = new AtomicLong();
        private final AtomicLong nbSkipped = new AtomicLong();
        private final AtomicLong nbFailed = new AtomicLong();

        void updated() {
            nbUpdated.incrementAndGet();
        }

        void unchanged() {
            nbUnchanged.incrementAndGet();
        }

        void skipped() {
            nbSkipped.incrementAndGet();
        }

        void failed() {
            nbFailed.incrementAndGet();
        }

        long nbUpdated() {
            return nbUpdated.get();
        }

        long nbUnchanged() {
            return nbUnchanged.get();
        }

        long nbSkipped() {
            return nbSkipped.get();
        }

        long nbFailed() {
            return nbFailed.get();
        }

        /** Documents whose language this run settled, the two outcomes an operator counts as done. */
        long nbProcessed() {
            return nbUpdated() + nbUnchanged();
        }
    }
}
