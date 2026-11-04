package org.icij.datashare.tasks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageActivityOptions;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.ContentTypeCategory;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhances documents stored in ElasticSearch with their content type category computed from their content type
 */
public class CategorizeStage implements StageProcessor<String, String> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;

    public CategorizeStage(Indexer indexer) {
        this.indexer = indexer;
    }

    @Override
    public Stage stage() {
        return Stage.CATEGORIZE;
    }

    @Override
    public Class<String> itemType() {
        return String.class;
    }

    @Override
    public StageResult process(List<String> batchDocumentsId, StageContext<String> ctx) {
        String projectName = ctx.project().getName();
        if (projectName == null) {
            throw new IllegalArgumentException("Cannot categorize documents on null project");
        }
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        int toProcess = batchDocumentsId.size();
        logger.info("Processing {} docs to add their content type category ", toProcess);
        for (String docId : batchDocumentsId) {
            if (ctx.isCancelled()) {
                break;
            }
            try {
                Document retrievedFromIndexer = indexer.get(projectName, DocReference.parse(docId));
                if (retrievedFromIndexer != null) {
                    enrichWithType(retrievedFromIndexer, projectName);
                    updated++;
                } else {
                    unchanged++;
                }
                ctx.emit(List.of(docId));
            } catch (Exception e) {
                logger.error("Error when categorizing {}", docId, e);
                failed++;
            }
        }

        logger.info("Processed Categorize Stage on {} documents", updated + unchanged);
        return new StageResult(updated, unchanged, toProcess - updated - unchanged - failed, failed);
    }

    @Override
    public StageActivityOptions activityOptions() {
        return StageProcessor.super.activityOptions();
    }

    private void enrichWithType(Document doc, String projectName) throws IOException {
        Document enrichedDoc =
                DocumentBuilder.from(doc).with(ContentTypeCategory.fromContentType(doc.getContentType())).build();
        indexer.update(projectName, enrichedDoc);
        logger.atDebug().setMessage(
                      () -> String.format("Added type %s to %s", enrichedDoc.getContentTypeCategory(), enrichedDoc.getId()))
              .log();
    }

}
