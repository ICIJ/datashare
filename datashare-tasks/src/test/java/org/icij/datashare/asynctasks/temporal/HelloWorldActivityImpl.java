package org.icij.datashare.asynctasks.temporal;

import static org.icij.datashare.asynctasks.temporal.TemporalHelper.taskWrapper;

import io.temporal.client.WorkflowClient;
import java.util.Map;
import java.util.concurrent.Callable;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskRepository;

public class HelloWorldActivityImpl extends TemporalActivityImpl<String, Callable<String>> implements HelloWorldActivity {

    public HelloWorldActivityImpl(TaskFactory factory, WorkflowClient client, TaskRepository taskRepository, Double progressWeight) {
        super(factory, client, taskRepository, progressWeight);
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
