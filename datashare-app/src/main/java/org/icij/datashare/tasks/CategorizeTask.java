package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.concurrent.atomic.AtomicLong;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.ContentTypeCategory;
import org.icij.datashare.text.indexing.Indexer;
import java.util.function.Function;

/**
 * Computes the contentTypeCategory field of documents based on their contentType in Elasticsearch.
 * This Task is required for documents that were indexed before the functionality to retrieve documents
 * based on their contentTypeCategory exists, or if the field value needs to be computed again from contentType.
 * It runs after {@link Stage#ENQUEUEIDX} to read the content already in the index.
 * If contentType field on the document is not found or empty, contentTypeCategory will be set to {@link ContentTypeCategory#OTHER}
 */
@TemporalSingleActivityWorkflow(name = "categorize", activityOptions = @ActivityOpts(timeout = "PT10M"))
@TaskGroup(TaskGroupType.Java)
public class CategorizeTask extends StageTask<String, String> implements Monitorable {
    private final Function<Double, Void> progressCallback;
    private final AtomicLong totalToProcess = new AtomicLong();

    @Inject
    public CategorizeTask(final Indexer indexer, final DocumentCollectionFactory<String> factory,
                          final DocumentCollectionFactory<String> outputFactory, final UpstreamGate.Factory gateFactory,
                          final StageRegistry stageRegistry, @Assisted Task<Long> taskView,
                          @Assisted final Function<Double, Void> progressCallback) {
        super(Stage.CATEGORIZE, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class,
              gateFactory.forTask(taskView), stageRegistry, outputFactory, String.class);
        this.progressCallback = progressCallback;
    }

    @Override
    public Long call() throws Exception {
        return super.call();
    }

    @Override
    protected void onEntryProcessed() {
        if (progressCallback != null) {
            progressCallback.apply(getProgressRate());
        }
    }

    @Override
    public double getProgressRate() {
        long total = totalToProcess.accumulateAndGet(inputQueue.size(), Math::max);
        return total == 0 ? 0 : (double) (total - inputQueue.size()) / total;
    }
}

