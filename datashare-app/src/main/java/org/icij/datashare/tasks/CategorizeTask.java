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
    public static final int NB_MAX_POLLS = 3;

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
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("enriching {} docs from inputQueue {} and adding them in {}", inputQueue.size(), inputQueue.getName(), outputQueue.getName());
        String docId;
        long nbMessages = 0;
        int nbMaxPolls = NB_MAX_POLLS;
        int pollingIntervalSeconds = 60;

        while (!(STRING_POISON.equals(docId = inputQueue.poll( (pollingIntervalSeconds * 1000), TimeUnit.MILLISECONDS)))
                && nbMaxPolls > 0) {
            try {
                if (docId != null) {
                    enrichWithType(indexer.get(project.getName(), docId));
                    nbMessages++;
                    processed.incrementAndGet();
                    if(progressCallback != null) {
                        progressCallback.apply(getProgressRate());
                    }
                    if(!outputQueue.offer(docId)){
                        logger.warn("unable to offer {} to queue {}", docId, outputQueue.getName());
                    }
                } else {
                    logger.info("will poll document queue again for pollingInterval={} seconds ({}/{})", pollingIntervalSeconds, nbMaxPolls, NB_MAX_POLLS);
                    nbMaxPolls--;
                }
            } catch (Exception e) {
                logger.error("error in CategorizeTask loop", e);
            }
        }
        if(STRING_POISON.equals(docId)) {
            outputQueue.add(STRING_POISON);
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
        if(doc == null || doc.getContentType() == null)
            return;
        Document enrichedDoc = DocumentBuilder.from(doc).with(ContentTypeCategory.fromContentType(doc.getContentType())).build();
        indexer.update(project.getName(), enrichedDoc);
        logger.atDebug().setMessage(() -> String.format("Added type %s to %s", enrichedDoc.getContentTypeCategory(), enrichedDoc.getId())).log();
    }
}

