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
import org.icij.datashare.text.Project;
import org.icij.datashare.text.artifact.Artifact;
import org.icij.datashare.text.artifact.ArtifactContext;
import org.icij.datashare.text.artifact.ArtifactProducer;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;

@TemporalSingleActivityWorkflow(name = "artifact", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class ArtifactTask extends PipelineTask<String> {
    private static final List<String> SOURCE_EXCLUDES = List.of("content", "content_translated");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Project project;
    private final Path artifactDir;
    private final int parallelism;
    private final ExecutorService executor;

    @Inject
    public ArtifactTask(DocumentCollectionFactory<String> factory, Indexer indexer, PropertiesProvider propertiesProvider, final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.ARTIFACT, taskView.getUser(), factory, propertiesProvider, String.class, gateFactory.forTask(taskView));
        this.indexer = indexer;
        project = Project.project(ArtifactStages.resolveProjectName(propertiesProvider));
        parallelism = Math.max(1, propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(1));
        artifactDir = Path.of(propertiesProvider.get(ARTIFACT_DIR_OPT).orElseThrow(() -> new IllegalArgumentException(String.format("cannot create artifact task with empty %s", ARTIFACT_DIR_OPT))));
        executor = Executors.newFixedThreadPool(parallelism, namedThreadFactory("artifact-worker"));
    }

    @Override
    public void cancel(boolean requeue) {
        // interrupt the task thread first (PipelineTask): that is what makes the blocking
        // future.get() in call() throw InterruptedException and surface the cancellation.
        // Then stop the worker pool so the workers themselves wind down promptly.
        super.cancel(requeue);
        executor.shutdownNow();
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("creating artifact cache in {} for project {} from queue {} with {} worker(s)", artifactDir, project, inputQueue.getName(), parallelism);
        AtomicLong nbDocs = new AtomicLong(0);
        AtomicLong nbSkipped = new AtomicLong(0);
        AtomicLong nbFailed = new AtomicLong(0);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < parallelism; i++) {
                futures.add(executor.submit(() -> runWorker(nbDocs, nbSkipped, nbFailed)));
            }
            int nbFailures = 0;
            Throwable firstCause = null;
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    logger.error("artifact worker terminated abnormally", e.getCause());
                    if (nbFailures == 0) {
                        firstCause = e.getCause();
                    }
                    nbFailures++;
                }
            }
            // The other drains end on their own interrupt check. Here future.get() only throws when
            // the task thread is interrupted while still waiting, which does not hold for a future
            // that had already completed when cancel() landed. Thread.interrupted() tests AND clears,
            // so the flag does not leak onto the runner thread with the InterruptedException.
            if (Thread.interrupted()) {
                throw new InterruptedException("cancelled while draining " + inputQueue.getName());
            }
            if (nbFailures > 0) {
                throw new IllegalStateException(String.format("%d of %d artifact worker(s) terminated abnormally", nbFailures, futures.size()), firstCause);
            }
        } finally {
            // single cleanup point for every path: normal completion, worker failure, and
            // cancellation (where the InterruptedException from future.get() propagates out and
            // TaskWorkerLoop records the run as cancelled). No awaitTermination() here: waiting
            // would only delay cancellation, and workers leave nothing in the queue to clean up.
            executor.shutdownNow();
        }
        if (nbSkipped.get() > 0) {
            logger.error("{} document(s) could not be retrieved from index {} and got no artifact cache, re-run the ARTIFACT stage for them", nbSkipped.get(), project.name);
        }
        if (nbFailed.get() > 0) {
            // Failed docs never got a terminal manifest entry, so isCurrent() is false for them
            // and a plain re-run already reprocesses exactly those (not --artifactsForce, which
            // would force-reprocess the entire corpus). Matches the nbSkipped guidance above.
            logger.error("{} document(s) failed artifact production in project {}, re-run the ARTIFACT stage for them", nbFailed.get(), project.name);
        }
        logger.info("exiting ArtifactTask loop after processing {} document(s).", nbDocs.get());
        return nbDocs.get();
    }

    private void runWorker(AtomicLong nbDocs, AtomicLong nbSkipped, AtomicLong nbFailed) {
        SourceExtractor extractor = createSourceExtractor();
        // Decide once per worker which artifact types to produce: an absent --artifacts flag
        // means all registered types (raw is the only one wired in this foundation).
        ArtifactRegistry registry = ArtifactRegistry.withDefaults();
        List<Artifact> selected = registry.select(propertiesProvider.get(ARTIFACTS_OPT).orElse(null));
        boolean force = ArtifactStages.force(propertiesProvider);
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository());
        Path projectRoot = artifactDir.resolve(project.name);
        // The interrupt check keeps cancellation prompt, since cancel() calls executor.shutdownNow()
        // while a worker may sit between two non-blocking polls.
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
                    // a Runnable cannot throw it: re-interrupt so call()'s check reports CANCELLED
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            if (isLegacySentinel(queueEntry)) {
                logger.warn("skipping legacy POISON sentinel in queue {}", inputQueue.getName());
                continue;
            }
            try {
                Document doc = getDocument(indexer, project.name, DocReference.parse(queueEntry), SOURCE_EXCLUDES);
                if (doc == null) {
                    nbSkipped.incrementAndGet();
                    continue;
                }
                // Each polled node is produced into its own content-addressed directory.
                Path docArtifactDir = ArtifactPath.dir(projectRoot, doc.getId());
                if (producer.run(selected, new ArtifactContext(project, doc, docArtifactDir, extractor), force)) {
                    nbDocs.incrementAndGet();
                } else {
                    nbFailed.incrementAndGet();
                }
            } catch (Throwable e) {
                if (causedByInterrupt(e)) {
                    Thread.currentThread().interrupt();
                    break;
                }
                logger.error("error in ArtifactTask loop", e);
                nbFailed.incrementAndGet();
            }
        }
    }

    protected SourceExtractor createSourceExtractor() {
        return new SourceExtractor(propertiesProvider);
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + counter.incrementAndGet());
            return thread;
        };
    }
}
