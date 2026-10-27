package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.tasks.temporal.StageActivityImpl;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.icij.datashare.text.indexing.elasticsearch.SimpleElasticsearchSpewer;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The INDEX stage as a registered processor: it holds the services of the process and builds an {@link IndexStage}
 * from the options of each batch, since the extractor and spewer depend on them. The options are the properties of
 * the run, plus the {@code taskId} (see {@link StageActivityImpl#TASK_ID_OPT}) that names its artifacts manifests.
 */
public class IndexStageProcessor implements StageProcessor<Path, String> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final LanguageGuesser languageGuesser;

    public IndexStageProcessor(Indexer indexer, LanguageGuesser languageGuesser) {
        this.indexer = indexer;
        this.languageGuesser = languageGuesser;
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
        PropertiesProvider properties = new PropertiesProvider(ctx.options());
        IndexOptions indexOptions = IndexOptions.of(properties);
        ArtifactOptions artifactOptions = properties.toRecord(ArtifactOptions.class);
        warnIfParseTimeoutDisabled(indexOptions);
        try (IndexStage stage = new IndexStage(indexOptions, artifactOptions,
                                               () -> ArtifactRegistry.withDefaults(indexOptions.extractor())
                                                                     .select(artifactOptions.artifacts()), false,
                                               (String) ctx.options().get(StageActivityImpl.TASK_ID_OPT),
                                               Extractor::new,
                                               recorder -> new SimpleElasticsearchSpewer(new FieldNames(), indexer,
                                                                                         languageGuesser, indexOptions,
                                                                                         artifactOptions.projectName(),
                                                                                         recorder))) {
            return stage.process(batch, ctx);
        }
    }

    private void warnIfParseTimeoutDisabled(IndexOptions indexOptions) {
        Duration parseTimeout = indexOptions.extractor().parseTimeout();
        if (parseTimeout != null && (parseTimeout.isZero() || parseTimeout.isNegative())) {
            logger.warn("parseTimeout is set to {}: the parse timeout is DISABLED. " +
                        "A pathological document can hang a worker indefinitely.", parseTimeout);
        }
    }
}
