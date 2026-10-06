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
import org.icij.datashare.text.artifact.ArtifactConfigurationException;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACTS_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PARSE_TIMEOUT;
import static org.icij.datashare.cli.DatashareCliOptions.PARSE_TIMEOUT_OPT;

@TemporalSingleActivityWorkflow(name = "artifact", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class ArtifactTask extends PipelineTask<String> {
    private static final List<String> SOURCE_EXCLUDES = List.of("content", "content_translated");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Project project;
    private final Path artifactDir;
    private final String taskId;

    @Inject
    public ArtifactTask(DocumentCollectionFactory<String> factory, Indexer indexer,
                        final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView,
                        @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.ARTIFACT, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class,
              gateFactory.forTask(taskView));
        this.indexer = indexer;
        taskId = taskView.id;
        project = Project.project(ArtifactStages.resolveProjectName(propertiesProvider));
        artifactDir = Path.of(propertiesProvider.get(ARTIFACT_DIR_OPT).orElseThrow(() -> new IllegalArgumentException(
                String.format("cannot create artifact task with empty %s", ARTIFACT_DIR_OPT))));
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("creating artifact cache in {} for project {} from queue {} with {} worker(s)", artifactDir,
                    project, inputQueue.getName(), nbWorkers);
        warnIfParseTimeoutIsIgnored();
        AtomicLong nbDocs = new AtomicLong(0);
        AtomicLong nbSkipped = new AtomicLong(0);
        AtomicLong nbFailed = new AtomicLong(0);
        runWorkers(() -> runWorker(nbDocs, nbSkipped, nbFailed));
        if (nbSkipped.get() > 0) {
            logger.error(
                    "{} document(s) could not be retrieved from index {} and got no artifact cache, re-run the ARTIFACT stage for them",
                    nbSkipped.get(), project.name);
        }
        if (nbFailed.get() > 0) {
            // Failed docs never got a terminal manifest entry, so isCurrent() is false for them
            // and a plain re-run already reprocesses exactly those (not --artifactsForce, which
            // would force-reprocess the entire corpus). Matches the nbSkipped guidance above.
            logger.error("{} document(s) failed artifact production in project {}, re-run the ARTIFACT stage for them",
                         nbFailed.get(), project.name);
        }
        logger.info("exiting ArtifactTask loop after processing {} document(s).", nbDocs.get());
        return nbDocs.get();
    }

    private void runWorker(AtomicLong nbDocs, AtomicLong nbSkipped, AtomicLong nbFailed) {
        SourceExtractor extractor = createSourceExtractor();
        // Decide once per worker which artifact types to produce: an absent --artifacts flag means the
        // whole catalog, raw, structure and page (see ArtifactRegistry#withDefaults).
        ArtifactRegistry registry = ArtifactRegistry.withDefaults(propertiesProvider);
        List<Artifact> selected = registry.select(propertiesProvider.get(ARTIFACTS_OPT).orElse(null));
        boolean force = ArtifactStages.force(propertiesProvider);
        // The producer owns what counts as a cancellation (see ArtifactProducer#isCancellation), so this
        // loop and the produce loop it drives cannot disagree about it.
        ArtifactProducer producer =
                new ArtifactProducer(new FilesystemManifestRepository(), workerPool::isShutdown, taskId);
        Path projectRoot = ArtifactPath.projectRoot(artifactDir, project.name);
        drainQueue(producer::isCancellation, queueEntry -> {
            try {
                Document doc = getDocument(indexer, project.name, DocReference.parse(queueEntry), SOURCE_EXCLUDES);
                if (doc == null) {
                    nbSkipped.incrementAndGet();
                    return;
                }
                // Each polled node is produced into its own content-addressed directory.
                Path docArtifactDir = ArtifactPath.dir(projectRoot, doc.getId());
                if (producer.run(selected, new ArtifactContext(project, doc, docArtifactDir, extractor), force)) {
                    nbDocs.incrementAndGet();
                } else {
                    nbFailed.incrementAndGet();
                }
            } catch (Error | ArtifactConfigurationException e) {
                // Neither is one document going wrong: an OutOfMemoryError leaves this worker on a heap it
                // has already exhausted, and a broken producer configuration fails every document the same
                // way, so both end the run rather than being counted once per document left in the queue.
                throw e;
            } catch (Throwable e) {
                if (producer.isCancellation(e)) {
                    Thread.currentThread().interrupt();
                    return;
                }
                logger.error("error in ArtifactTask loop", e);
                nbFailed.incrementAndGet();
            }
        });
    }

    // --parseTimeout is an Extractor option and this stage does not go through the Extractor, so a
    // pathological parse here is bounded by nothing but the activity's one-day timeout.
    private void warnIfParseTimeoutIsIgnored() {
        // Filtered on the default, not just on presence: the option is set on every run.
        propertiesProvider.get(PARSE_TIMEOUT_OPT).filter(value -> !DEFAULT_PARSE_TIMEOUT.equals(value)).ifPresent(
                value -> logger.warn("parseTimeout is set to {} but does not apply to the " +
                                     "ARTIFACT stage: a document whose parse never returns holds its worker until " +
                                     "the task times out.", value));
    }

    protected SourceExtractor createSourceExtractor() {
        return new SourceExtractor(propertiesProvider);
    }
}
