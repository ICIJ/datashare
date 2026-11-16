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
 * Enqueues the file paths a search query selects, so the next stage extracts them again with
 * whatever extraction options the run carries. The mirror image of {@link ScanIndexTask}, which
 * fills the report map so INDEX skips what it already extracted.
 */
@TemporalSingleActivityWorkflow(name = "scan-query", activityOptions = @ActivityOpts(timeout = "P7D"))
@TaskGroup(TaskGroupType.Java)
public class ScanQueryTask extends StageTask<Void, Path> {
    @Inject
    public ScanQueryTask(DocumentCollectionFactory<Path> factory, final StageRegistry registry,
                         @Assisted Task<Long> taskView, @Assisted Function<Double, Void> ignored) {
        super(Stage.SCANQUERY, taskView.getUser(), null, new PropertiesProvider(taskView.args), Void.class,
              UpstreamGate.NONE, registry, factory, Path.class);
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
