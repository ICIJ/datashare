package org.icij.datashare.asynctasks.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Map;

@WorkflowInterface
public interface DoNothingWorkflow {
    @WorkflowMethod(name = "org.icij.datashare.asynctasks.temporal.DoNothingTask")
    String run(Map<String, Object> args) throws Exception;
}
