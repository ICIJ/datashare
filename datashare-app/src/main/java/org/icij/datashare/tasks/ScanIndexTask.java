package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;

@TemporalSingleActivityWorkflow(name = "scan-index", activityOptions = @ActivityOpts(timeout = "P7D"))
@TaskGroup(TaskGroupType.Java)
public class ScanIndexTask extends StageTask<Void, Void> {
    @Inject
    public ScanIndexTask(final StageRegistry registry, @Assisted Task<Long> taskView,
                         @Assisted Function<Double, Void> ignored) {
        super(Stage.SCANIDX, taskView.getUser(), null, new PropertiesProvider(taskView.args), Void.class,
              UpstreamGate.NONE, registry);
    }

    /** A source stage has no input queue. */
    @Override
    protected String getInputQueueName() {
        return null;
    }

    /** Declared here, not only inherited: TemporalWorkflowGenerator reads the output type from the annotated class. */
    @Override
    public Long call() throws Exception {
        return super.call();
    }
}
