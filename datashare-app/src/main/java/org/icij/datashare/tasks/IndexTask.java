package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.artifact.Artifact;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.DocumentConsumer;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueueDrainer;
import org.icij.extract.report.Reporter;
import org.icij.task.Options;
import org.icij.task.annotation.Option;
import org.icij.task.annotation.OptionsClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import org.icij.time.HumanDuration;

import static java.lang.Math.max;
import static java.lang.String.valueOf;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.PropertiesProvider.REPORT_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.*;

@TemporalSingleActivityWorkflow(name = "index-documents", activityOptions = @ActivityOpts(timeout = "P30D"))
@OptionsClass(Extractor.class)
@OptionsClass(DocumentFactory.class)
@OptionsClass(DocumentQueueDrainer.class)
@Option(name = DEFAULT_PROJECT_OPT, description = "the default project name")
@Option(name = "projectName", description = "task project name")
@TaskGroup(TaskGroupType.Java)
public class IndexTask extends PipelineTask<Path> implements Monitorable{
    private static final Path PATH_POISON = Paths.get("POISON");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Extractor extractor;
    private final DocumentQueueDrainer<Path> drainer;
    private final DocumentConsumer consumer;
    private final Consumer<Path> progressTrackConsumer;
    private long totalToProcess;

    private final AtomicInteger processed = new AtomicInteger(0);
    // entries the drainer counted as consumed but that were not documents (legacy sentinel)
    private final AtomicInteger skipped = new AtomicInteger(0);
    private final Integer parallelism;
    private final Integer indexTimeout;

    @Inject
    public IndexTask(final ElasticsearchSpewer spewer, final DocumentCollectionFactory<Path> factory, final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progressCallback) throws IOException {
        super(Stage.INDEX, taskView.getUser(), factory, new PropertiesProvider(taskView.args), Path.class, gateFactory.forTask(taskView));
        parallelism = propertiesProvider.get(PARALLELISM_OPT).map(Integer::parseInt).orElse(Runtime.getRuntime().availableProcessors());
        indexTimeout = getIndexTimeout();
        warnIfParseTimeoutDisabled();

        // --artifacts is opt-in and requires a place to write. Resolve the project the same way
        // ElasticsearchSpewer.configure resolves the ES index name (prefer projectName, then
        // defaultProject) so the manifest dir, the embedded raw bytes, and the ES index all agree.
        Path artifactProjectRoot = null;
        if (propertiesProvider.get(ARTIFACTS_OPT).isPresent()) {
            String dir = propertiesProvider.get(ARTIFACT_DIR_OPT)
                    .orElseThrow(() -> new IllegalArgumentException("--artifacts requires --artifactDir"));
            String projectName = propertiesProvider.get("projectName")
                    .orElse(propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
            artifactProjectRoot = Path.of(dir).resolve(projectName);
            List<Artifact> selected = ArtifactRegistry.withDefaults().select(propertiesProvider.get(ARTIFACTS_OPT).get());
            boolean force = Boolean.parseBoolean(propertiesProvider.get(ARTIFACTS_FORCE_OPT).orElse("false"));
            spewer.setManifestRecorder(new ManifestRecorder(new FilesystemManifestRepository(), artifactProjectRoot, selected, force));
        }

        Options<String> allTaskOptions = options().createFrom(Options.from(taskView.args));
        ((ElasticsearchSpewer) spewer.configure(allTaskOptions)).createIndexIfNotExists();

        DocumentFactory documentFactory = new DocumentFactory().configure(allTaskOptions);
        this.extractor = createExtractor(documentFactory, allTaskOptions);
        if (artifactProjectRoot != null) {
            // extract-lib writes embedded raw bytes only when embedOutput is set; the INDEX path
            // never sets it otherwise. Point it at the same project root as the manifest recorder so
            // --artifacts actually produces the payloads the manifest entries reference.
            this.extractor.setEmbedOutputPath(artifactProjectRoot);
        }

        consumer = new DocumentConsumer(spewer, this.extractor, this.parallelism);
        progressTrackConsumer = path -> {
            // Transitional. Redis queue keys survive upgrades, so a pre-21.16 run can leave a
            // "POISON" path in this queue. Skip it instead of trying to extract a file named POISON.
            if (PATH_POISON.equals(path)) {
                logger.warn("skipping legacy POISON entry in queue {}", inputQueue.getName());
                // DocumentQueueDrainer counts every entry it hands us, so discount this one
                // to keep the returned total and the progress rate about real documents only
                skipped.incrementAndGet();
                return;
            }
            consumer.accept(path);
            processed.incrementAndGet();
            if (progressCallback != null) {
                progressCallback.apply(getProgressRate());
            }
        };
        if (propertiesProvider.getProperties().get(REPORT_NAME_OPT) != null) {
            logger.info("report map enabled with name set to {}", propertiesProvider.getProperties().get(REPORT_NAME_OPT));
            consumer.setReporter(new Reporter(factory.createMap(propertiesProvider.getProperties().get(REPORT_NAME_OPT).toString())));
        }
        drainer = new DocumentQueueDrainer<>(inputQueue, progressTrackConsumer).configure(allTaskOptions);
        // The drainer has no notion of an upstream stage: without a latch it stops on its first
        // empty poll, which is only right when the producer has already finished.
        if (gate != UpstreamGate.NONE) {
            drainer.setLatch(new UpstreamSealableLatch(this::drained, UPSTREAM_POLL_INTERVAL_MS));
        }
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("Processing up to {} file(s) in parallel", parallelism);
        try {
            totalToProcess = drainer.drain().get() - skipped.get();
            drainer.shutdown();
            drainer.awaitTermination(10, SECONDS); // drain is finished
            logger.info("drained {} documents. Waiting for consumer to shutdown", totalToProcess);

            consumer.shutdown();
            // documents could be currently processed
            while (!consumer.awaitTermination(indexTimeout, MINUTES)) {
                logger.info("Consumer has not terminated yet.");
            }

            if (consumer.getReporter() != null) consumer.getReporter().close();
            logger.info("exiting");
            return totalToProcess;
        } finally {
            extractor.close();
        }
    }

    protected Extractor createExtractor(DocumentFactory documentFactory, Options<String> options) {
        return new Extractor(documentFactory, options);
    }

    /**
     * Retrieves the index timeout option in minutes based on app properties.
     *
     * @return The number of minutes.
     */
    private int getIndexTimeout() {
        return Integer.parseInt(propertiesProvider.get(INDEX_TIMEOUT_OPT).orElse(valueOf(DEFAULT_INDEX_TIMEOUT)));
    }

    private void warnIfParseTimeoutDisabled() {
        propertiesProvider.get(PARSE_TIMEOUT_OPT).ifPresent(value -> {
            try {
                Duration parseTimeout = HumanDuration.parse(value);
                if (parseTimeout.isZero() || parseTimeout.isNegative()) {
                    logger.warn("parseTimeout is set to {}: the parse timeout is DISABLED. " +
                            "A pathological document can hang a worker indefinitely.", value);
                }
            } catch (RuntimeException e) {
                // Any parse failure (DateTimeParseException, NumberFormatException, ...) is intentionally
                // ignored here: this is a diagnostic-only check and must never fail task construction.
                // Leave duration validation to the extractor.
            }
        });
    }

    @Override
    public double getProgressRate() {
        totalToProcess = max(inputQueue.size(), totalToProcess);
        return totalToProcess == 0 ? 0 : (double)(totalToProcess - inputQueue.size()) / totalToProcess;
    }
}
