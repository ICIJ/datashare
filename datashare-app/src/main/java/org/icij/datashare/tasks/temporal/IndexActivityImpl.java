package org.icij.datashare.tasks.temporal;

import io.temporal.activity.Activity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.tasks.ArtifactOptions;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.artifact.Artifact;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.artifact.ArtifactType;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.AbstractElasticSearchSpewer;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.icij.time.HumanDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACTS_OPT;

public class IndexActivityImpl implements IndexActivity {
    private final Logger logger = LoggerFactory.getLogger(IndexActivityImpl.class);
    private final PropertiesProvider propertiesProvider;
    private final Indexer indexer;
    private final LanguageGuesser languageGuesser;

    public IndexActivityImpl(final PropertiesProvider propertiesProvider, final Indexer indexer,
                             final LanguageGuesser languageGuesser) {
        this.propertiesProvider =
                propertiesProvider; // TODO: to be removed after ArtifactStages refactoring into ArtifactOptions or the like
        this.indexer = indexer;
        this.languageGuesser = languageGuesser;
    }

    @Override
    public void index(IndexOptions indexOptions, List<Path> paths, String index) {
        warnIfParseTimeoutDisabled(indexOptions);

        //TODO créer classe ?
        AbstractElasticSearchSpewer spewer =
                new AbstractElasticSearchSpewer(new FieldNames(), indexer, languageGuesser, indexOptions) {
                    @Override
                    protected void postIndexation(Document document, String indexName) {
                        // No event to generate, the workflow can continue with data in ES
                    }

                    @Override
                    public void close() {
                        //Nothing to do
                    }
                };
        try {
            spewer.createIndexIfNotExists(index);
        } catch (IOException e) {
            logger.error("failed to create index {}", index, e);
            return;
        }

        DocumentFactory documentFactory = new DocumentFactory().configure(indexOptions.documentFactoryOptions());
        String taskId = Activity.getExecutionContext().getInfo().getWorkflowId();
        ArtifactOptions artifactOptions = propertiesProvider.toRecord(ArtifactOptions.class);
        try (Extractor extractor = new Extractor(documentFactory, indexOptions.extractorOptions())) {
            // Opt-in artifact generation (--artifacts): the same manifest recording IndexTask does at
            // index time, so raw embeds captured during this parse aren't lost to a separate stage.
            artifactOptions.artifactProjectRoot().ifPresent(projectRoot -> {
                List<Artifact> selected = ArtifactRegistry.withDefaults(propertiesProvider)
                                                          .select(propertiesProvider.get(ARTIFACTS_OPT).orElse(null));
                boolean rawSelected = selected.stream().anyMatch(artifact -> artifact.type() == ArtifactType.RAW);
                if (rawSelected) {
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
