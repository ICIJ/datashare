package org.icij.datashare.asynctasks.shutdownfixtures;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;
import org.icij.datashare.asynctasks.temporal.TemporalWorkflowImpl;

public class InterruptibleWorkflowImpl extends TemporalWorkflowImpl implements InterruptibleWorkflow {
    private final InterruptibleActivity activity;

    public InterruptibleWorkflowImpl() {
        // short timeout: if the interrupted attempt is never reported, the test does not wait the 2 minutes of the others
        this.activity = Workflow.newActivityStub(
                InterruptibleActivity.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build());
    }

    @Override
    public String interruptible(Map<String, Object> args) {
        return this.activity.interruptible(args);
    }
}
