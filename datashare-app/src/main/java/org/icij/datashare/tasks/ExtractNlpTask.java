package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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

@TemporalSingleActivityWorkflow(name = "ner", activityOptions = @ActivityOpts(timeout = "P7D"))
@TaskGroup(TaskGroupType.Java)
public class ExtractNlpTask extends StageTask<String, Void> implements Monitorable {
    private final Function<Double, Void> progressCallback;
    private final AtomicInteger processed = new AtomicInteger(0);

    @Inject
    public ExtractNlpTask(DocumentCollectionFactory<String> factory, StageRegistry registry,
                          final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView,
                          @Assisted final Function<Double, Void> progressCallback) {
        super(Stage.NLP, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class,
              gateFactory.forTask(taskView), registry);
        this.progressCallback = progressCallback;
    }

    /** Declared here, not only inherited: TemporalWorkflowGenerator reads the output type from the annotated class. */
    @Override
    public Long call() throws Exception {
        return super.call();
    }

    @Override
    protected void onEntryProcessed() {
        processed.incrementAndGet();
        progressCallback.apply(getProgressRate());
    }

    @Override
    public double getProgressRate() {
        int done = processed.get();
        int totalToProcess = done + inputQueue.size();
        return totalToProcess == 0 ? 0 : (double) done / totalToProcess;
    }
}
