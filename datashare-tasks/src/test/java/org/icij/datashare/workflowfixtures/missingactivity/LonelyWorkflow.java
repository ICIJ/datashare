package org.icij.datashare.workflowfixtures.missingactivity;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.icij.datashare.asynctasks.temporal.DiscoverableWorkflow;

import java.util.Map;

/**
 * A workflow interface whose implementation exists but whose {@code LonelyActivityImpl} does not.
 */
@WorkflowInterface
@DiscoverableWorkflow
public interface LonelyWorkflow {
    @WorkflowMethod(name = "lonely")
    String run(Map<String, Object> args);
}
