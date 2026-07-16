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
import org.icij.datashare.text.artifact.RawArtifact;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACTS_FORCE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_POLLING_INTERVAL_SEC;
import static org.icij.datashare.cli.DatashareCliOptions.PARALLELISM_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.POLLING_INTERVAL_SECONDS_OPT;

@TemporalSingleActivityWorkflow(name = "artifact", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class ArtifactTask extends PipelineTask<String> {
    private static final List<String> SOURCE_EXCLUDES = List.of("content", "content_translated");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Project project;
    private final Path artifactDir;
    private final int pollingInterval;
    private final int parallelism;
    private final ExecutorService executor;

    @Inject
    public ArtifactTask(DocumentCollectionFactory<String> factory, Indexer indexer, PropertiesProvider propertiesProvider, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.ARTIFACT, taskView.getUser(), factory, propertiesProvider, String.class);
        this.indexer = indexer;
        project = Project.project(propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
        pollingInterval = Integer.parseInt(propertiesProvider.get(POLLING_INTERVAL_SECONDS_OPT).orElse(DEFAULT_POLLING_INTERVAL_SEC));
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
        logger.info("creating artifact cache in {} for project {} from queue {} with {} worker(s) and polling interval {}s", artifactDir, project, inputQueue.getName(), parallelism, pollingInterval);
        // Clean any stale STRING_POISON left in the input queue by a PRIOR aborted run before
        // starting workers. Doing this at startup (rather than teardown) means the cleanup no
        // longer depends on this run's workers having joined: a worker stuck in a
        // non-interruptible parse past the old awaitTermination window can no longer race the
        // drain and leave a sentinel that zeroes/truncates the next run. This run's own trailing
        // straggler poison (left by the propagation relay at teardown) is simply left in the
        // queue; the NEXT run's startup drain cleans it (self-healing).
        drainStaleResidualPoison();
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
            if (nbFailures > 0) {
                throw new IllegalStateException(String.format("%d of %d artifact worker(s) terminated abnormally", nbFailures, futures.size()), firstCause);
            }
        } finally {
            // single cleanup point for every path: normal completion, worker failure, and
            // cancellation (where the InterruptedException from future.get() propagates out and
            // TaskWorkerLoop records the run as cancelled). No awaitTermination() here: waiting
            // delayed cancellation, and the residual-poison cleanup now runs at startup so it no
            // longer needs the workers to have joined.
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
        ArtifactRegistry registry = new ArtifactRegistry(List.of(new RawArtifact()));
        List<Artifact> selected = registry.select(propertiesProvider.get(ARTIFACTS_OPT).orElse(null));
        boolean force = Boolean.parseBoolean(propertiesProvider.get(ARTIFACTS_FORCE_OPT).orElse("false"));
        ArtifactProducer producer = new ArtifactProducer(new FilesystemManifestRepository());
        Path projectRoot = artifactDir.resolve(project.name);
        try {
            String queueEntry;
            // POISON is the primary, timing-independent termination signal offered by
            // EnqueueFromIndexTask once it is done enqueuing; re-offer it so every other
            // worker in this pool also sees it and terminates (poison propagation, as
            // DeduplicateTask does across stages). The null-poll exit is only a fallback and
            // must tolerate TRANSIENT empties: EnqueueFromIndexTask produces concurrently, so a
            // worker that drains everything enqueued-so-far can get a null before the producer's
            // next scroll batch (and before the trailing poison) arrives. Exiting on the first
            // null would strand every doc enqueued afterwards. So mirror ExtractNlpTask: allow up
            // to NB_MAX_POLLS CONSECUTIVE null polls before giving up, resetting the budget on any
            // real entry.
            int nbMaxPolls = ExtractNlpTask.NB_MAX_POLLS;
            while (nbMaxPolls > 0) {
                queueEntry = inputQueue.poll(pollingInterval, TimeUnit.SECONDS);
                if (STRING_POISON.equals(queueEntry)) {
                    inputQueue.offer(STRING_POISON);
                    break;
                }
                if (queueEntry == null) {
                    nbMaxPolls--;
                    continue;
                }
                nbMaxPolls = ExtractNlpTask.NB_MAX_POLLS;
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
                    logger.error("error in ArtifactTask loop", e);
                    nbFailed.incrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // The input queue name is static (<queueName>:artifact) and is never deleted between runs
    // (MemoryDocumentQueue.close() is a no-op, RedisDocumentQueue.close() only closes the
    // client). The poison-propagation relay in runWorker always leaves exactly one re-offered
    // STRING_POISON behind once the last worker reads it and breaks. Left uncleaned, that
    // sentinel sits at the head of the next ARTIFACT run (a documented, supported re-run
    // workflow via --artifactsForce) and terminates every worker before it processes a single
    // doc ref, silently reporting 0 processed. Drain it here, at STARTUP of the next run.
    //
    // Running at startup (before submitting workers) rather than at teardown is what makes this
    // safe without waiting on the previous run's workers: the stale poison is whatever a prior
    // run left in the queue, and this run's own poison has not been produced yet
    // (EnqueueFromIndexTask emits it concurrently, at the END of its enqueue). A prior worker
    // stuck in a non-interruptible parse therefore cannot race this drain.
    //
    // On the kill/cancellation path the queue can hold real doc refs BEHIND the poison too:
    // workers were interrupted before draining that far, e.g. [realDocX, realDocY, POISON].
    // Stopping at the first non-poison entry would offer realDocX back and quit, leaving POISON
    // in the queue behind it, so the next run's workers hit the stale POISON after only a couple
    // of docs and terminate early, stranding the rest. So instead drain the ENTIRE queue
    // (bounded by whatever is present right now: poll() is non-blocking and returns null once
    // empty), discard every STRING_POISON regardless of position, and re-offer only the real doc
    // refs, in their original FIFO order. If this drain happens to remove an already-arrived
    // legit poison, the null-retry fallback in runWorker still terminates workers correctly. This
    // is intentionally NOT inputQueue.delete(): delete() would also wipe those real, unprocessed
    // entries, reintroducing silent data loss on the failure/cancellation paths.
    private void drainStaleResidualPoison() {
        List<String> realEntries = new ArrayList<>();
        String entry;
        while ((entry = inputQueue.poll()) != null) {
            if (!STRING_POISON.equals(entry)) {
                realEntries.add(entry);
            }
        }
        for (String realEntry : realEntries) {
            inputQueue.offer(realEntry);
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
