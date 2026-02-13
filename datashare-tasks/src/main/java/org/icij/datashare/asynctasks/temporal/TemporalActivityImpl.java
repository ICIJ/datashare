package org.icij.datashare.asynctasks.temporal;

import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
import static org.icij.datashare.asynctasks.temporal.TemporalHelper.taskWrapper;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import org.icij.datashare.asynctasks.ProgressSmoother;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskFactoryHelper;
import org.icij.datashare.user.User;

public abstract class TemporalActivityImpl<R, T extends Callable<R>> {
    private final TaskFactory factory;
    private final Double progressWeight;
    private final WorkflowClient client;

    public TemporalActivityImpl(TaskFactory factory, WorkflowClient client, Double progressWeight) {
        this.factory = factory;
        this.client = client;
        this.progressWeight = progressWeight;
    }

    abstract protected Class<T> getTaskClass();

    protected TaskFactory getTaskFactory() {
        return factory;
    }

    protected BiConsumer<String, Double> getProgressFn(ActivityInfo info) {
        WorkflowStub workflow = client.newUntypedWorkflowStub(info.getWorkflowId());
        return (ignored, progress) -> workflow.signal("progress",
            new ProgressSignal(info.getRunId(), info.getActivityId(), progress, this.progressWeight));
    }

    protected Set<Class<? extends Exception>> getRetriables() {
        return Set.of();
    }

    protected R run(Map<String, Object> args) throws Exception {
        return taskWrapper(rethrowFunction(inputArgs -> {
            ActivityInfo info = Activity.getExecutionContext().getInfo();
            String runId = info.getRunId();
            User user = new User((Map<String, Object>) inputArgs.get("user"));
            Task<?> task = new Task<>(runId, getTaskClass().getSimpleName(), user, inputArgs);
            BiConsumer<String, Double> progressFn = getProgressFn(info);
            ProgressSmoother smoothedProgress = new ProgressSmoother(progressFn, 1000);
            Callable<R> taskFn = (Callable<R>) TaskFactoryHelper.createTaskCallable(
                getTaskFactory(), getTaskClass().getName(), task, task.progress(smoothedProgress)
            );
            progressFn.accept(task.getId(), 0.0);
            R result = taskFn.call();
            progressFn.accept(task.getId(), 1.0);
            return result;
        }), args, getRetriables());
    }
}