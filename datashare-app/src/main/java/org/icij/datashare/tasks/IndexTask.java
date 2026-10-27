package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewerFactory;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
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
@Option(name = OCR_MIN_CONFIDENCE_OPT,
        description = "OCR confidence, from 0 to 100, under which an image's text is not used to detect its language. Only applies when the OCR records a confidence: the language is chosen per image, or the OCR type is TESS4J")
@TaskGroup(TaskGroupType.Java)
public class IndexTask extends PipelineTask<Path> implements Monitorable {
    private static final Path PATH_POISON = Paths.get("POISON");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ElasticsearchSpewerFactory spewerFactory;
    private final DocumentCollectionFactory<Path> collectionFactory;
    private final Options<String> allTaskOptions;
    private final IndexOptions indexOptions;
    private final Function<Double, Void> progressCallback;
    private long totalToProcess;
    private final AtomicInteger processed = new AtomicInteger(0);
    // entries the drainer counted as consumed but that were not documents (legacy sentinel)
    private final AtomicInteger skipped = new AtomicInteger(0);
    private final String taskId;

    @Inject
    public IndexTask(final ElasticsearchSpewerFactory spewerFactory, final DocumentCollectionFactory<Path> factory,
                     final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView,
                     @Assisted final Function<Double, Void> progressCallback) throws IOException {
        super(Stage.INDEX, taskView.getUser(), factory, new PropertiesProvider(taskView.args), Path.class,
              gateFactory.forTask(taskView));
        this.spewerFactory = spewerFactory;
        this.collectionFactory = factory;
        this.progressCallback = progressCallback;
        taskId = taskView.id;
        indexOptions = IndexOptions.of(propertiesProvider);
        warnIfParseTimeoutDisabled();

        allTaskOptions = options().createFrom(Options.from(taskView.args));
    }

    @Override
    public Long call() throws Exception {
        super.call();
        // Opt-in artifact generation is wired in the stage's constructor, called from here (not in this task's
        // constructor) so a bad --artifacts config surfaces as a clean task error rather than a
        // reflective-construction NackException that requeues forever.
        ArtifactOptions artifactOptions = propertiesProvider.toRecord(ArtifactOptions.class);
        try (IndexStage stage = new IndexStage(indexOptions, artifactOptions,
                                               () -> ArtifactRegistry.withDefaults(indexOptions.extractor())
                                                                     .select(propertiesProvider.get(ARTIFACTS_OPT)
                                                                                               .orElse(null)),
                                               new PipelineHelper(propertiesProvider).stages.contains(Stage.ARTIFACT),
                                               taskId, this::createExtractor,
                                               recorder -> spewerFactory.create(artifactOptions.projectName(),
                                                                                indexOptions.languageDetection()
                                                                                            .ocrMinConfidence(),
                                                                                recorder))) {
            DocumentConsumer consumer =
                    new DocumentConsumer(stage.spewer(), stage.extractor(), indexOptions.parallelism());
            DocumentQueueDrainer<Path> drainer = createDrainer(consumer);
            logger.info("Processing up to {} file(s) in parallel", indexOptions.parallelism());
            totalToProcess = drainer.drain().get() - skipped.get();
            drainer.shutdown();
            drainer.awaitTermination(10, SECONDS); // drain is finished
            logger.info("drained {} documents. Waiting for consumer to shutdown", totalToProcess);

            consumer.shutdown();
            // documents could be currently processed
            while (!consumer.awaitTermination(indexOptions.indexTimeout(), MINUTES)) {
                logger.info("Consumer has not terminated yet.");
            }

            if (consumer.getReporter() != null)
                consumer.getReporter().close();
            logger.info("exiting");
            return totalToProcess;
        }
    }

    private DocumentQueueDrainer<Path> createDrainer(DocumentConsumer consumer) {
        Consumer<Path> progressTrackConsumer = path -> {
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
            logger.info("report map enabled with name set to {}",
                        propertiesProvider.getProperties().get(REPORT_NAME_OPT));
            consumer.setReporter(new Reporter(
                    collectionFactory.createMap(propertiesProvider.getProperties().get(REPORT_NAME_OPT).toString())));
        }
        DocumentQueueDrainer<Path> drainer =
                new DocumentQueueDrainer<>(inputQueue, progressTrackConsumer).configure(allTaskOptions);
        // The drainer has no notion of an upstream stage: without a latch it stops on its first
        // empty poll, which is only right when the producer has already finished.
        if (gate != UpstreamGate.NONE) {
            drainer.setLatch(new UpstreamSealableLatch(this::drained, UPSTREAM_POLL_INTERVAL_MS));
        }
        return drainer;
    }

    protected Extractor createExtractor(DocumentFactory documentFactory, Options<String> options) {
        return new Extractor(documentFactory, options);
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
        return totalToProcess == 0 ? 0 : (double) (totalToProcess - inputQueue.size()) / totalToProcess;
    }
}
