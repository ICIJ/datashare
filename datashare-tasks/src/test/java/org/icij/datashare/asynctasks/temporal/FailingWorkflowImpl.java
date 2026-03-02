package org.icij.datashare.asynctasks.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;

public class FailingWorkflowImpl extends TemporalWorkflowImpl implements FailingWorkflow {
    private final FailingActivity fail;

    public FailingWorkflowImpl() {
        this.fail = Workflow.newActivityStub(
            FailingActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build()
        );
    }

    @Override
    public void fail(Map<String, Object> args) {
        this.fail.failing(args);
    }
}
