package org.icij.datashare.workflowfixtures.badname;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.Map;

/**
 * A workflow interface that does not end with {@code Workflow}, so no activity implementation name can be derived
 * from it.
 */
@WorkflowInterface
public interface NotSuffixed {
    @WorkflowMethod(name = "not-suffixed")
    String run(Map<String, Object> args);
}
