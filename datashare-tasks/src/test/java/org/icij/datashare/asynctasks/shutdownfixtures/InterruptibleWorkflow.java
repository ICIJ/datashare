package org.icij.datashare.asynctasks.shutdownfixtures;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Map;

@WorkflowInterface
public interface InterruptibleWorkflow {
    @WorkflowMethod(name = "interruptible")
    String interruptible(Map<String, Object> args);
}
