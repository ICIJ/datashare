package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.function.Function;
import java.util.stream.Stream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;

@TemporalSingleActivityWorkflow(name = "artifact", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class ArtifactTask extends StageTask<String, Void> {
    @Inject
    public ArtifactTask(DocumentCollectionFactory<String> factory, StageRegistry registry,
                        final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView,
                        @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.ARTIFACT, taskView.getUser(), taskView.id, factory, new PropertiesProvider(taskView.args),
              String.class, gateFactory.forTask(taskView), registry);
    }

    @Override
    public Long call() throws Exception {
        return super.call();
    }
}
