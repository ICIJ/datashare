package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.List;
import java.util.function.Function;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import static org.icij.datashare.cli.DatashareCliOptions.NEXT_STAGE_OPT;

@TemporalSingleActivityWorkflow(name = "enqueue-ner-tasks-from-index", activityOptions = @ActivityOpts(timeout = "P7D"))
@TaskGroup(TaskGroupType.Java)
public class EnqueueFromIndexTask extends StageTask<String, String> {
    @Inject
    public EnqueueFromIndexTask(final DocumentCollectionFactory<String> factory, final StageRegistry registry,
                                @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> ignored) {
        super(Stage.ENQUEUEIDX, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class,
              UpstreamGate.NONE, registry, factory, String.class);
    }

    @Override
    protected StageResult run(StageContext<String> ctx) throws InterruptedException {
        StageResult result = processor().process(List.of(), ctx);
        if (Thread.interrupted()) {
            throw new InterruptedException("cancelled while enqueuing from the index");
        }
        return result;
    }

    @Override
    protected String getInputQueueName() {
        return null;
    }

    @Override
    protected String getOutputQueueName() {
        String nextStage = propertiesProvider.get(NEXT_STAGE_OPT).orElse(null);
        if (nextStage == null) {
            return super.getOutputQueueName();
        }
        try {
            return new PipelineHelper(propertiesProvider).getQueueNameFor(
                    EnqueueFromIndexStage.parseNextStage(nextStage));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    //TODO : This method is required because of the TemporalSingleActivityWorkflow annotation,
    // but will this Task be used as a SingleActivity Task ?
    @Override
    public Long call() throws Exception {
        return super.call();
    }
}
