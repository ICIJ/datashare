package org.icij.datashare.asynctasks.routingfixtures;

import java.util.concurrent.Callable;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;

/**
 * Kept in its own package so that discovering it does not affect the fixtures other {@code WorkflowRegistry} tests
 * assert on. The {@code taskQueue} of {@link ActivityOpts} is deliberately left at its default: proving that the
 * generated workflow still reaches the activity confirms the queue actually served is the one the routing strategy
 * resolves at runtime, not the one baked into this annotation.
 */
@TemporalSingleActivityWorkflow(name = "greeting", activityOptions = @ActivityOpts(timeout = "P1D"))
public class GreetingTask implements Callable<String> {
    @Override
    public String call() {
        return "";
    }
}
