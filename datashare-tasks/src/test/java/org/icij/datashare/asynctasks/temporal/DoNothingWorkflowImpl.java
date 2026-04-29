package org.icij.datashare.asynctasks.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;

public class DoNothingWorkflowImpl extends TemporalWorkflowImpl implements DoNothingWorkflow {
    private final DoNothingActivity activity;

    public DoNothingWorkflowImpl() {
        this.activity = Workflow.newActivityStub(
            DoNothingActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(1)).build()
        );
    }

    @Override
    public String run(Map<String, Object> args) throws Exception {
        return activity.run(args);
    }
}
