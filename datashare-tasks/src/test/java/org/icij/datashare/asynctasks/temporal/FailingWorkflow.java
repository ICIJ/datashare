package org.icij.datashare.asynctasks.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Map;

@WorkflowInterface
public interface FailingWorkflow {
    @WorkflowMethod(name = "failing")
    void fail(Map<String, Object> args);
}
