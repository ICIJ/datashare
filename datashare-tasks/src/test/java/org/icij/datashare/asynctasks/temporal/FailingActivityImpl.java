package org.icij.datashare.asynctasks.temporal;

import static org.icij.datashare.asynctasks.temporal.TemporalHelper.taskWrapper;

import io.temporal.client.WorkflowClient;
import java.util.Map;
import java.util.concurrent.Callable;
import org.icij.datashare.asynctasks.TaskFactory;

public class FailingActivityImpl extends TemporalActivityImpl<String, Callable<String>> implements FailingActivity {
    public FailingActivityImpl(TaskFactory factory, WorkflowClient client, Double progressWeight) {
        super(factory, client, progressWeight);
    }

    @Override
    public void failing(Map<String, Object> args) {
        taskWrapper(() -> {
            throw new RuntimeException("this is a failure");
        });
    }

    @Override
    protected Class<Callable<String>> getTaskClass() {
        return null;
    }
}
