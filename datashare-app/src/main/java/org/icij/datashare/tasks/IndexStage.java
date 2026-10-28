package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.artifact.Artifact;
import org.icij.datashare.text.artifact.ArtifactType;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Extracts each path and indexes it through the spewer, then emits the reference of each indexed document. The
 * constructor builds what a run needs (extractor, artifact recording, spewer, index) and throws if that fails, an
 * {@link UncheckedIOException} when the index cannot be created (Temporal retries the activity on it);
 * {@link #close()} releases the extractor. The caller decides how long that lives: one batch for the Temporal
 * activity, the whole run for IndexTask, which feeds {@link #extractor()} and {@link #spewer()} to its own parallel
 * consumer. Idempotent: a re-run overwrites the document.
 */
public class IndexStage implements AutoCloseable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ArtifactOptions artifactOptions;
    private final Supplier<List<Artifact>> selectedArtifacts;
    private final boolean artifactStageRuns;
    private final String taskId;
    private final Extractor extractor;
    private final ElasticsearchSpewer spewer;

    /**
     * @param selectedArtifacts only called when the run opted in to --artifacts
     * @param artifactStageRuns true when ARTIFACT follows INDEX in the pipeline, so it produces what this stage does not
     * @param extractorFactory builds the extractor from the options, a seam for tests
     * @param spewerFactory builds the spewer around the manifest recorder, null when the run records no artifacts
     */
    public IndexStage(IndexOptions indexOptions, ArtifactOptions artifactOptions,
                      Supplier<List<Artifact>> selectedArtifacts, boolean artifactStageRuns, String taskId,
                      BiFunction<DocumentFactory, Options<String>, Extractor> extractorFactory,
                      Function<ManifestRecorder, ? extends ElasticsearchSpewer> spewerFactory) {
        this.artifactOptions = artifactOptions;
        this.selectedArtifacts = selectedArtifacts;
        this.artifactStageRuns = artifactStageRuns;
        this.taskId = taskId;
        extractor = extractorFactory.apply(new DocumentFactory().configure(indexOptions.documentFactory().toOptions()),
                                           indexOptions.extractor().toOptions());
        try {
            spewer = spewerFactory.apply(manifestRecorder());
            spewer.createIndexIfNotExists();
        } catch (IOException e) {
            extractor.close();
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            extractor.close();
            throw e;
        }
    }

    public Extractor extractor() {
        return extractor;
    }

    public ElasticsearchSpewer spewer() {
        return spewer;
    }

    public StageResult process(List<Path> batch, StageContext<String> ctx) {
        spewer.onIndexed(document -> ctx.emit(List.of(DocReference.fromDocument(document).toQueueEntry())));
        StageResult result = StageResult.EMPTY;
        for (Path path : batch) {
            if (ctx.isCancelled()) {
                break;
            }
            try {
                extractor.extract(path, spewer);
                result = result.plus(new StageResult(1, 0, 0, 0));
            } catch (Exception e) {
                // one corrupt document must not fail the batch: Temporal would retry it forever on that document
                logger.error("failed to extract/index {}", path, e);
                result = result.plus(new StageResult(0, 0, 0, 1));
            }
        }
        return result;
    }

    @Override
    public void close() {
        extractor.close();
    }

    /** The recorder for a run that opted in to --artifacts, null otherwise. */
    private ManifestRecorder manifestRecorder() {
        return artifactOptions.artifactProjectRoot().map(projectRoot -> {
            List<Artifact> selected = selectedArtifacts.get();
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
}
