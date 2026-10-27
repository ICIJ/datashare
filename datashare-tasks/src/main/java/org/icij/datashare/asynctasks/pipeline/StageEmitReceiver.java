package org.icij.datashare.asynctasks.pipeline;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import java.util.List;
import org.icij.datashare.Stage;

/** How a stage running as an activity hands what it emits to the workflow that runs the pipeline. */
@WorkflowInterface
public interface StageEmitReceiver {
    @SignalMethod
    void onEmitted(Stage from, List<Object> items);
}
