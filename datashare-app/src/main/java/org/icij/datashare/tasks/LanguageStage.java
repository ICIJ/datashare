package org.icij.datashare.tasks;

import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.datashare.text.indexing.elasticsearch.LanguageOptions;
import org.icij.spewer.FieldNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import static java.util.Optional.ofNullable;
import static org.icij.extract.ocr.ParserWithConfidence.OCR_CONFIDENCE;

/**
 * Re-detects the language of every referenced document and rewrites the language field in place, for corpora
 * indexed by an older guesser. It detects on exactly what index time detects on
 * ({@link LanguageGuesser#guess(String, java.nio.file.Path, Object, int)}), file-name fallback included, so a
 * re-run cannot downgrade a document the INDEX stage got right. Idempotent: a re-run redoes the same documents.
 */
public class LanguageStage implements StageProcessor<String, Void> {
    private static final List<String> SOURCE_EXCLUDES = List.of("content_translated");
    private static final String OCR_CONFIDENCE_KEY = new FieldNames().forMetadata(OCR_CONFIDENCE.getName());
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final LanguageGuesser languageGuesser;
    private final int minOcrConfidence;

    public LanguageStage(Indexer indexer, LanguageGuesser languageGuesser, LanguageOptions options) {
        this.indexer = indexer;
        this.languageGuesser = languageGuesser;
        this.minOcrConfidence = options.ocrMinConfidence();
    }

    @Override
    public Stage stage() {
        return Stage.LANGUAGE;
    }

    @Override
    public StageResult process(List<String> batch, StageContext<Void> ctx) {
        StageResult result = StageResult.EMPTY;
        for (String queueEntry : batch) {
            if (ctx.isCancelled()) {
                break;
            }
            result = result.plus(detect(queueEntry, ctx.project().name));
        }
        return result;
    }

    private StageResult detect(String queueEntry, String project) {
        try {
            DocReference ref = DocReference.parse(queueEntry);
            Document document = indexer.get(project, ref.id(), ref.routing(), SOURCE_EXCLUDES);
            if (document == null) {
                // indexer.get() also returns null on fetch failures (it logs them as ERROR), not only on missing ids
                logger.warn("document <{}> could not be retrieved from index {} (missing document or index fetch " +
                            "error), skipping", ref.id(), project);
                return new StageResult(0, 0, 1, 0);
            }
            return updateLanguage(project, document, ref);
        } catch (IOException | RuntimeException e) {
            if (PipelineTask.causedByInterrupt(e)) {
                // a cancelled worker ends its drain: the flag is set back, ctx.isCancelled() reads it
                Thread.currentThread().interrupt();
                return StageResult.EMPTY;
            }
            // one document going wrong is counted and re-runnable, not thrown
            logger.error("language detection failed for queue entry <{}>", queueEntry, e);
            return new StageResult(0, 0, 0, 1);
        }
    }

    private StageResult updateLanguage(String project, Document document, DocReference ref) throws IOException {
        Object ocrConfidence =
                ofNullable(document.getMetadata()).map(metadata -> metadata.get(OCR_CONFIDENCE_KEY)).orElse(null);
        Language guess =
                languageGuesser.guess(document.getContent(), document.getPath(), ocrConfidence, minOcrConfidence);
        if (guess == document.getLanguage()) {
            return new StageResult(0, 1, 0, 0);
        }
        indexer.update(project, document.getId(), Map.of("language", guess.name()), ref.routing());
        return new StageResult(1, 0, 0, 0);
    }
}
