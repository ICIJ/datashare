package org.icij.datashare.asynctasks.pipeline;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;
import java.util.Map;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageActivityOptions;
import org.icij.datashare.asynctasks.StageResult;

/** Runs any pipeline: the first stage is the source, what each stage emits is forwarded to the next one. */
@WorkflowInterface
public interface PipelineWorkflow {
    /**
     * @param activityOptions How each stage is configured to be run in Temporal
     * @return what each stage did, all its batches summed
     */
    @WorkflowMethod(name = "PipelineWorkflow")
    Map<Stage, StageResult> run(List<Stage> stages, Map<String, Object> options,
                                Map<Stage, StageActivityOptions> activityOptions);
}
