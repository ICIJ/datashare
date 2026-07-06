package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.ContentTypeCategory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_POLLING_INTERVAL_SEC;
import static org.icij.datashare.cli.DatashareCliOptions.POLLING_INTERVAL_SECONDS_OPT;

/**
 * Computes the contentTypeCategory field of documents based on their contentType in Elasticsearch.
 * This Task is required for documents that were indexed before the functionality to retrieve documents
 * based on their contentTypeCategory exists, or if the field value needs to be computed again from contentType.
 * It runs after {@link Stage#ENQUEUEIDX} to read the content already in the index.
 * If contentType field on the document is not found or empty, contentTypeCategory will be set to {@link ContentTypeCategory#OTHER}
 */
@TaskGroup(TaskGroupType.Java)
@TemporalSingleActivityWorkflow(name = "categorize", activityOptions = @ActivityOpts(timeout = "PT10M"))
public class CategorizeTask extends PipelineTask<String> implements Monitorable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private static final int NB_MAX_POLLS = 3;
    private final float pollingIntervalSeconds;
    private final AtomicInteger processed = new AtomicInteger(0);
    private final Function<Double, Void> progressCallback;
    private final Indexer indexer;
    private final Project project;
    @Inject
    public CategorizeTask(final Indexer indexer, final DocumentCollectionFactory<String> factory, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progressCallback) {
        super(Stage.CATEGORIZE, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class);
        this.progressCallback = progressCallback;
        this.indexer = indexer;
        project = Project.project(ofNullable((String)taskView.args.get(DEFAULT_PROJECT_OPT)).orElse(DEFAULT_DEFAULT_PROJECT));
        pollingIntervalSeconds = Float.parseFloat(ofNullable((String) taskView.args.get(POLLING_INTERVAL_SECONDS_OPT)).orElse(DEFAULT_POLLING_INTERVAL_SEC));
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("enriching {} docs from inputQueue {} and adding them in {}", inputQueue.size(), inputQueue.getName(), outputQueue.getName());
        String docId;
        long nbMessages = 0;
        int nbMaxPolls = NB_MAX_POLLS;

        while (nbMaxPolls > 0) {
            long pollTimeoutMs = Math.max(1L, (long) (pollingIntervalSeconds * 1000));
            docId = inputQueue.poll(pollTimeoutMs, TimeUnit.MILLISECONDS);
            try {
                if (docId == null) {
                    logger.info("will poll document queue again for pollingInterval={} seconds ({}/{})", pollingIntervalSeconds, nbMaxPolls, NB_MAX_POLLS);
                    nbMaxPolls--;
                } else if (isPoison(docId)) {
                    logger.debug("skipping legacy POISON entry in queue {}", inputQueue.getName());
                } else {
                    Document retrievedFromIndexer = indexer.get(project.getName(), docId);
                    if (retrievedFromIndexer == null) {
                        logger.warn("Unable to retrieve document {} from indexer. Cannot add it's contentTypeCategory", docId);
                    } else {
                        enrichWithType(retrievedFromIndexer);
                    }
                    nbMessages++;
                    processed.incrementAndGet();
                    progressCallback.apply(getProgressRate());
                    if (!outputQueue.offer(docId)) {
                        logger.warn("unable to offer {} to queue {}", docId, outputQueue.getName());
                    }
                }
            } catch (Exception e) {
                logger.error("error in CategorizeTask loop", e);
            }
        }
        logger.info("exiting CategorizeTask loop after {} messages.", nbMessages);
        return nbMessages;
    }

    @Override
    public double getProgressRate() {
        int done = processed.get();
        int totalToProcess = done + inputQueue.size();
        return totalToProcess == 0 ? 0 : (double) done / totalToProcess;
    }

    private void enrichWithType(Document doc) throws IOException {
        Document enrichedDoc = DocumentBuilder.from(doc).with(ContentTypeCategory.fromContentType(doc.getContentType())).build();
        indexer.update(project.getName(), enrichedDoc);
        logger.atDebug().setMessage(() -> String.format("Added type %s to %s", enrichedDoc.getContentTypeCategory(), enrichedDoc.getId())).log();
    }
}

