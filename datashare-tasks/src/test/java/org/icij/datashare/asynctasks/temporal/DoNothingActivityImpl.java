package org.icij.datashare.asynctasks.temporal;

import io.temporal.client.WorkflowClient;
import java.util.Map;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskRepository;

public class DoNothingActivityImpl extends TemporalActivityImpl<String, DoNothingTask> implements DoNothingActivity {

    public DoNothingActivityImpl(TaskFactory factory, WorkflowClient client, TaskRepository taskRepository, Double progressWeight) {
        super(factory, client, taskRepository, progressWeight);
    }

    @Override
    protected Class<DoNothingTask> getTaskClass() {
        return DoNothingTask.class;
    }

    @Override
    public String run(Map<String, Object> args) throws Exception {
        return super.run(args);
    }
}
