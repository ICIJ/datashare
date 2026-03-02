package org.icij.datashare.asynctasks.temporal;

import static org.icij.datashare.asynctasks.temporal.TemporalHelper.taskWrapper;

import io.temporal.client.WorkflowClient;
import java.util.Map;
import java.util.concurrent.Callable;
import org.icij.datashare.asynctasks.TaskFactory;

public class HelloWorldActivityImpl extends TemporalActivityImpl<String, Callable<String>> implements HelloWorldActivity {

    public HelloWorldActivityImpl(TaskFactory factory, WorkflowClient client, Double progressWeight) {
        super(factory, client, progressWeight);
    }

    @Override
    public String helloWorld(Map<String, Object> args) {
        return taskWrapper(() -> "hello " + args.get("name"));
    }

    @Override
    protected Class<Callable<String>> getTaskClass() {
        return null;
    }
}
