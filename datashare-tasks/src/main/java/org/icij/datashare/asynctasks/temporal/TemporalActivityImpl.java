package org.icij.datashare.asynctasks.temporal;

import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
import static org.icij.datashare.asynctasks.temporal.TemporalHelper.taskWrapper;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import org.icij.datashare.asynctasks.ProgressSmoother;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFactory;
import org.icij.datashare.asynctasks.TaskFactoryHelper;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TemporalActivityImpl<R, T extends Callable<R>> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final TaskFactory factory;
    private final WorkflowClient client;
    private final TaskRepository taskRepository;
    private final Double progressWeight;

    public TemporalActivityImpl(TaskFactory factory, WorkflowClient client, TaskRepository taskRepository, Double progressWeight) {
        this.factory = factory;
        this.client = client;
        this.taskRepository = taskRepository;
        this.progressWeight = progressWeight;
    }

    abstract protected Class<T> getTaskClass();

    protected TaskFactory getTaskFactory() {
        return factory;
    }

    protected BiConsumer<String, Double> getProgressFn(ActivityInfo info) {
        WorkflowStub workflow = client.newUntypedWorkflowStub(info.getWorkflowId());
        String taskId = info.getWorkflowId();
        return (ignored, progress) -> {
            workflow.signal("progress", new ProgressSignal(info.getRunId(), info.getActivityId(), progress, this.progressWeight));
            try {
                logger.debug("Setting progress of {} to {}", taskId, progress);
                Task<?> task = taskRepository.getTask(taskId);
                task.setProgress(progress);
                taskRepository.update(task);
            } catch (IOException | RuntimeException e) {
                logger.warn("failed to update progress for task {}", taskId, e);
            }
        };
    }

    protected Set<Class<? extends Exception>> getRetriables() {
        return Set.of();
    }

    protected R run(Map<String, Object> args) throws Exception {
        return taskWrapper(rethrowFunction(inputArgs -> {
            ActivityInfo info = Activity.getExecutionContext().getInfo();
            User user = Optional.ofNullable((HashMap<String, Object>) inputArgs.get("user"))
                .map(User::new)
                .orElse(null);
            // TODO : BatchSearchRunner relies on the Task.id to retrieve the Queries to run from the DB.
            // TODO : This is a design that relies on unwritten convention, so it should be changed
            Task<?> task = new Task<>(info.getWorkflowId(), getTaskClass().getSimpleName(), user, inputArgs);
            BiConsumer<String, Double> progressFn = getProgressFn(info);
            ProgressSmoother smoothedProgress = new ProgressSmoother(progressFn, 10);
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