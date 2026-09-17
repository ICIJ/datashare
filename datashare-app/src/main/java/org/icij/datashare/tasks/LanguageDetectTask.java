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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;

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
        executor = Executors.newFixedThreadPool(parallelism);
    }

    @Override
    public void cancel(boolean requeue) {
        super.cancel(requeue);
        executor.shutdownNow();
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("re-detecting document languages for project {} from queue {} with {} worker(s)",
                    project.name, inputQueue.getName(), parallelism);
        AtomicLong nbUpdated = new AtomicLong(0);
        AtomicLong nbUnchanged = new AtomicLong(0);
        AtomicLong nbSkipped = new AtomicLong(0);
        AtomicLong nbFailed = new AtomicLong(0);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < parallelism; i++) {
                futures.add(executor.submit(() -> runWorker(nbUpdated, nbUnchanged, nbSkipped, nbFailed)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("cancelled while draining " + inputQueue.getName());
            }
        } finally {
            executor.shutdownNow();
        }
        logger.info("language re-detection done: {} updated, {} unchanged, {} skipped, {} failed",
                    nbUpdated.get(), nbUnchanged.get(), nbSkipped.get(), nbFailed.get());
        if (nbSkipped.get() + nbFailed.get() > 0) {
            logger.error("{} document(s) were skipped or failed, re-run the LANGUAGE stage for them",
                         nbSkipped.get() + nbFailed.get());
        }
        return nbUpdated.get() + nbUnchanged.get();
    }

    private void runWorker(AtomicLong nbUpdated, AtomicLong nbUnchanged, AtomicLong nbSkipped, AtomicLong nbFailed) {
        while (!Thread.currentThread().isInterrupted()) {
            String queueEntry;
            try {
                queueEntry = inputQueue.poll();
            } catch (RuntimeException e) {
                if (causedByInterrupt(e)) {
                    Thread.currentThread().interrupt();
                    break;
                }
                throw e;
            }
            if (queueEntry == null) {
                if (drained()) {
                    break;
                }
                try {
                    Thread.sleep(UPSTREAM_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            if (isLegacySentinel(queueEntry)) {
                continue;
            }
            DocReference ref = DocReference.parse(queueEntry);
            try {
                Document doc = getDocument(indexer, project.name, ref, SOURCE_EXCLUDES);
                if (doc == null) {
                    nbSkipped.incrementAndGet();
                    continue;
                }
                Language guess = languageGuesser.guess(doc.getContent());
                if (guess == doc.getLanguage()) {
                    nbUnchanged.incrementAndGet();
                } else {
                    indexer.update(project.name, doc.getId(), Map.of("language", guess.name()), ref.routing());
                    nbUpdated.incrementAndGet();
                }
            } catch (Exception e) {
                if (causedByInterrupt(e)) {
                    Thread.currentThread().interrupt();
                    break;
                }
                logger.error("language detection failed for document <{}>", ref.id(), e);
                nbFailed.incrementAndGet();
            }
        }
    }
}
