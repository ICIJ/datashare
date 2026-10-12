package org.icij.datashare.asynctasks.temporal;

import static org.icij.datashare.asynctasks.temporal.TemporalHelper.taskWrapper;

import java.util.Map;
import java.util.concurrent.Callable;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskRepository;

public class FailingActivityImpl extends TemporalActivityImpl<String, Callable<String>> implements FailingActivity {
    public FailingActivityImpl(TaskFactory factory, TemporalInterlocutor temporal, TaskRepository taskRepository, Double progressWeight) {
        super(factory, temporal, taskRepository, progressWeight);
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
