package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.nio.file.Path;
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

/**
 * filters the document queue with extracted docs
 */
@TemporalSingleActivityWorkflow(name = "deduplicate-documents", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class DeduplicateTask extends StageTask<Path, Path> {
    @Inject
    public DeduplicateTask(final DocumentCollectionFactory<Path> factory, final StageRegistry registry,
                           final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView,
                           @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.DEDUPLICATE, taskView.getUser(), taskView.id, factory, new PropertiesProvider(taskView.args),
              Path.class, gateFactory.forTask(taskView), registry, factory, Path.class);
    }

    /** Declared here, not only inherited: TemporalWorkflowGenerator reads the output type from the annotated class. */
    @Override
    public Long call() throws Exception {
        return super.call();
    }
}
