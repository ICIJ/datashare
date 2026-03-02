package org.icij.datashare.asynctasks.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;

public class HelloWorldWorkflowImpl extends TemporalWorkflowImpl implements HelloWorldWorkflow {
    private final HelloWorldActivity doNothing;

    public HelloWorldWorkflowImpl() {
        this.doNothing = Workflow.newActivityStub(
            HelloWorldActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(2)).build()
        );
    }

    @Override
    public String helloWorld(Map<String, Object> args) {
        return this.doNothing.helloWorld(args);
    }
}
