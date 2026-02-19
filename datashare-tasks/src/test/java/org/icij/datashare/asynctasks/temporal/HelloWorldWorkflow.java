package org.icij.datashare.asynctasks.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Map;

@WorkflowInterface
public interface HelloWorldWorkflow {
    @WorkflowMethod(name = "hello-world")
    String helloWorld(Map<String, Object> args);
}
