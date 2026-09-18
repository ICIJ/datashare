package org.icij.datashare.workflowfixtures.missingimpl;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.Map;

/**
 * A workflow interface with no {@code MissingImplWorkflowImpl} alongside it: discovering this package must fail
 * loudly rather than leave the workflow unserved.
 */
@WorkflowInterface
public interface MissingImplWorkflow {
    @WorkflowMethod(name = "missing-impl")
    String run(Map<String, Object> args);
}
