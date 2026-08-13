package org.icij.datashare.workflowfixtures.missingactivity;

import java.util.Map;

public class LonelyWorkflowImpl implements LonelyWorkflow {
    @Override
    public String run(Map<String, Object> args) {
        return "lonely";
    }
}
