package org.icij.datashare.tasks.temporal;

import io.temporal.activity.Activity;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.tasks.ArtifactOptions;
import org.icij.datashare.tasks.IndexStage;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.artifact.ArtifactRegistry;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.IndexOptions;
import org.icij.datashare.text.indexing.elasticsearch.SimpleElasticsearchSpewer;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexActivityImpl implements IndexActivity {
    private final Logger logger = LoggerFactory.getLogger(IndexActivityImpl.class);
    private final Indexer indexer;
    private final LanguageGuesser languageGuesser;

    public IndexActivityImpl(final Indexer indexer, final LanguageGuesser languageGuesser) {
        this.indexer = indexer;
        this.languageGuesser = languageGuesser;
    }

    @Override
    public void index(IndexOptions indexOptions, ArtifactOptions artifactOptions, List<Path> paths, String index) {
        warnIfParseTimeoutDisabled(indexOptions);

        try (IndexStage stage = new IndexStage(
                indexOptions, artifactOptions,
                () -> ArtifactRegistry.withDefaults(indexOptions.extractor()).select(artifactOptions.artifacts()),
                false, Activity.getExecutionContext().getInfo().getWorkflowId(), Extractor::new,
                recorder -> new SimpleElasticsearchSpewer(new FieldNames(), indexer, languageGuesser,
                                                          indexOptions, index, recorder))) {
            StageResult result = stage.process(paths, new StageContext<>() {
                public Project project() { return Project.project(index); }
                public Map<String, Object> options() { return Map.of(); }
                public boolean isCancelled() { return Thread.currentThread().isInterrupted(); }
                public void emit(List<String> out) { /* the workflow reads the documents from the index */ }
            });
            logger.info("indexed {} document(s) in {}, {} failed", result.updated(), index, result.failed());
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
