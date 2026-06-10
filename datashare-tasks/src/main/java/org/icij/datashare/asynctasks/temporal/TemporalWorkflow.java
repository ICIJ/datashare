package org.icij.datashare.asynctasks.temporal;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;

@WorkflowInterface
public interface TemporalWorkflow {
    @SignalMethod
    void progress(ProgressSignal progressSignal);

    @QueryMethod(name = "progress")
    double getProgress();
}
