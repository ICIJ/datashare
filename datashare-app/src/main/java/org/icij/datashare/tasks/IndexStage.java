package org.icij.datashare.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BiFunction;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.artifact.Artifact;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.artifact.ArtifactType;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACTS_OPT;

/**
 * Extracts each path and indexes it, then emits the reference of each indexed document. It holds the services of the
 * process; what depends on the run (the options, the extractor, the spewer and the index they write to) is built on
 * first use and kept by the {@link StageContext}, so it lives as long as the executor decides: one batch for an
 * activity, the whole run for a queue task. Idempotent: a re-run overwrites the document.
 * <p>
 * Per-path failures are counted: one corrupt document must not fail a batch, which Temporal would retry forever.
 * What cannot be set up (the index) is thrown.
 */
public class IndexStage implements StageProcessor<Path, String> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final LanguageGuesser languageGuesser;
    private final BiFunction<DocumentFactory, Options<String>, Extractor> extractorFactory;

    /** What the options of a run say, parsed once per context. */
    private record Config(PropertiesProvider properties, IndexOptions indexOptions, ArtifactOptions artifactOptions) {}

    public IndexStage(Indexer indexer, LanguageGuesser languageGuesser) {
        this(indexer, languageGuesser, Extractor::new);
    }

    /** @param extractorFactory builds the extractor from the options, a seam for tests */
    public IndexStage(Indexer indexer, LanguageGuesser languageGuesser,
                      BiFunction<DocumentFactory, Options<String>, Extractor> extractorFactory) {
        this.indexer = indexer;
        this.languageGuesser = languageGuesser;
        this.extractorFactory = extractorFactory;
    }

    @Override
    public Stage stage() {
        return Stage.INDEX;
    }

    @Override
    public Class<Path> itemType() {
        return Path.class;
    }

    @Override
    public StageResult process(List<Path> batch, StageContext<String> ctx) {
        Extractor extractor = ctx.resource(Extractor.class, () -> newExtractor(ctx));
        ElasticsearchSpewer spewer = ctx.resource(ElasticsearchSpewer.class, () -> newSpewer(extractor, ctx));
        StageResult result = StageResult.EMPTY;
        for (Path path : batch) {
            if (ctx.isCancelled()) {
                break;
            }
            try {
                extractor.extract(path, spewer);
                result = result.plus(new StageResult(1, 0, 0, 0));
            } catch (Exception e) {
                logger.error("failed to extract/index {}", path, e);
                result = result.plus(new StageResult(0, 0, 0, 1));
            }
        }
        return result;
    }

    private Config config(StageContext<String> ctx) {
        return ctx.resource(Config.class, () -> {
            PropertiesProvider properties = new PropertiesProvider(ctx.options());
            IndexOptions indexOptions = IndexOptions.of(properties);
            warnIfParseTimeoutDisabled(indexOptions);
            return new Config(properties, indexOptions, properties.toRecord(ArtifactOptions.class));
        });
    }

    private Extractor newExtractor(StageContext<String> ctx) {
        IndexOptions indexOptions = config(ctx).indexOptions();
        return extractorFactory.apply(new DocumentFactory().configure(indexOptions.documentFactory().toOptions()),
                                      indexOptions.extractor().toOptions());
    }

    private ElasticsearchSpewer newSpewer(Extractor extractor, StageContext<String> ctx) {
        Config config = config(ctx);
        IndexOptions indexOptions = config.indexOptions();
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(new FieldNames(), indexer, languageGuesser, indexOptions,
                                                             config.artifactOptions().projectName(),
                                                             indexOptions.languageDetection().ocrMinConfidence(),
                                                             manifestRecorder(config, extractor, (String) ctx.options()
                                                                                                             .get(StageContext.TASK_ID_OPT)));
        try {
            spewer.createIndexIfNotExists();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        spewer.onIndexed(document -> ctx.emit(List.of(DocReference.fromDocument(document).toQueueEntry())));
        return spewer;
    }

    /** The recorder for a run that opted in to --artifacts, null otherwise. */
    private ManifestRecorder manifestRecorder(Config config, Extractor extractor, String taskId) {
        ArtifactOptions artifactOptions = config.artifactOptions();
        return artifactOptions.artifactProjectRoot().map(projectRoot -> {
            List<Artifact> selected = ArtifactRegistry.withDefaults(config.indexOptions().extractor())
                                                      .select(config.properties().get(ARTIFACTS_OPT).orElse(null));
            boolean artifactStageRuns = new PipelineHelper(config.properties()).stages.contains(Stage.ARTIFACT);
            boolean rawSelected = selected.stream().anyMatch(artifact -> artifact.type() == ArtifactType.RAW);
            // Only raw falls out of the streaming parse (see ManifestRecorder): a selection naming any
            // other type reads as a promise this stage does not keep, so say so rather than drop it in
            // silence. Silent when ARTIFACT is coming, since the run does produce them then: a warning
            // that fires on a correct run is one nobody reads. Once per run, not per document.
            List<String> notAtIndexTime = selected.stream().map(Artifact::type).filter(type -> type != ArtifactType.RAW)
                                                  .map(ArtifactType::token).toList();
            if (!notAtIndexTime.isEmpty() && !artifactStageRuns) {
                logger.warn("--artifacts selects {}, which the INDEX stage does not produce, and ARTIFACT is not in " +
                            "--stages: {}", notAtIndexTime, rawSelected ? "only the 'raw' entry will be recorded." :
                                                            "this stage records nothing and writes no embedded payload; add 'raw' to " +
                                                            "--artifacts to keep the embedded-download cache.");
            }
            // extract-lib spools every embedded document's bytes here during the parse. This payload is
            // not manifest-only: SourceExtractor.hasCachedEmbeddedSource also reads it straight off disk
            // to let DocumentVerifier serve an oversized embed past embeddedDocumentDownloadMaxSize. A
            // selection that skips it here trades that cache away, and a large embed 413s instead.
            if (rawSelected || artifactStageRuns) {
                extractor.setEmbedOutputPath(projectRoot);
            }
            return new ManifestRecorder(new FilesystemManifestRepository(), projectRoot, selected,
                                        artifactOptions.force(), taskId);
        }).orElse(null);
    }

    private void warnIfParseTimeoutDisabled(IndexOptions indexOptions) {
        Duration parseTimeout = indexOptions.extractor().parseTimeout();
        if (parseTimeout != null && (parseTimeout.isZero() || parseTimeout.isNegative())) {
            logger.warn("parseTimeout is set to {}: the parse timeout is DISABLED. " +
                        "A pathological document can hang a worker indefinitely.", parseTimeout);
        }
    }
}
