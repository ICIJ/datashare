package org.icij.datashare.tasks.temporal;

import io.temporal.activity.Activity;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.icij.datashare.tasks.ArtifactOptions;
import org.icij.datashare.text.artifact.Artifact;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.artifact.ArtifactType;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.AbstractElasticSearchSpewer;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.icij.datashare.text.indexing.elasticsearch.SimpleElasticsearchSpewer;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.icij.time.HumanDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexActivityImpl implements IndexActivity {
    private final Logger logger = LoggerFactory.getLogger(IndexActivityImpl.class);
    private final Indexer indexer;
    private final LanguageGuesser languageGuesser;

    public IndexActivityImpl(final Indexer indexer,
                             final LanguageGuesser languageGuesser) {
        this.indexer = indexer;
        this.languageGuesser = languageGuesser;
    }

    @Override
    public void index(IndexOptions indexOptions, ArtifactOptions artifactOptions, List<Path> paths, String index) {
        warnIfParseTimeoutDisabled(indexOptions);

        AbstractElasticSearchSpewer spewer =
                new SimpleElasticsearchSpewer(new FieldNames(), indexer, languageGuesser, indexOptions);
        try {
            spewer.createIndexIfNotExists(index);
        } catch (IOException e) {
            logger.error("failed to create index {}", index, e);
            return;
        }

        DocumentFactory documentFactory = new DocumentFactory().configure(indexOptions.documentFactoryOptions());
        String taskId = Activity.getExecutionContext().getInfo().getWorkflowId();
        try (Extractor extractor = new Extractor(documentFactory, indexOptions.extractorOptions())) {
            // Opt-in artifact generation (--artifacts): the same manifest recording IndexTask does at
            // index time, so raw embeds captured during this parse aren't lost to a separate stage.
            artifactOptions.artifactProjectRoot().ifPresent(projectRoot -> {
                List<Artifact> selected = ArtifactRegistry.withDefaults(indexOptions).select(artifactOptions.artifacts());
                if (artifactOptions.artifacts().contains(ArtifactType.RAW)) {
                    extractor.setEmbedOutputPath(projectRoot);
                }
                spewer.setManifestRecorder(
                        new ManifestRecorder(new FilesystemManifestRepository(), projectRoot, selected,
                                             artifactOptions.force(), taskId));
            });

            for (Path path : paths) {
                try {
                    extractor.extract(path, spewer);
                } catch (Exception e) {
                    // A single corrupt/unreadable document must not fail the whole batch: Temporal
                    // would otherwise retry the same batch forever on that one document.
                    logger.error("failed to extract/index {}", path, e);
                }
            }
        }
    }

    private void warnIfParseTimeoutDisabled(IndexOptions indexOptions) {
        String value = indexOptions.parseTimeout();
        if (value == null) {
            return;
        }
        try {
            Duration parseTimeout = HumanDuration.parse(value);
            if (parseTimeout.isZero() || parseTimeout.isNegative()) {
                logger.warn("parseTimeout is set to {}: the parse timeout is DISABLED. " +
                            "A pathological document can hang a worker indefinitely.", value);
            }
        } catch (RuntimeException e) {
            // Diagnostic-only check: any parse failure here must never fail activity execution.
        }
    }
}
