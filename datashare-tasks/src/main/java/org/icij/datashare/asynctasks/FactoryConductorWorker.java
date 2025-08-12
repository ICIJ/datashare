package org.icij.datashare.asynctasks;

import com.netflix.conductor.client.http.TaskClient;
import com.netflix.conductor.client.worker.Worker;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.metadata.tasks.TaskResult.Status;
import com.netflix.conductor.sdk.workflow.executor.task.NonRetryableException;
import com.netflix.conductor.sdk.workflow.executor.task.TaskContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.function.Function;

public class FactoryConductorWorker implements Worker {
    private final String name;
    private final Method factoryMethod;
    private final TaskFactory factory;
    private int pollingInterval = 100;
    private final TaskClient taskClient;

    public FactoryConductorWorker(TaskClient taskClient, TaskFactory factory, String name, Method factoryMethod) {
        this.taskClient = taskClient;
        this.factory = factory;
        this.name = name;
        this.factoryMethod = factoryMethod;
    }

    public String getTaskDefName() {
        return this.name;
    }

    public TaskResult execute(com.netflix.conductor.common.metadata.tasks.Task task) {
        TaskResult result;
        Task<?> taskView = toICIJTask(task);

        Function<Double, Void> progressCallback = makeProgress(task);
        try {
            Callable<?> taskFn = (Callable<?>) factoryMethod.invoke(factory, taskView, progressCallback);
            Object invocationResult = taskFn.call();
            TaskContext context = TaskContext.set(task);
            result = context.getTaskResult();
            result.getOutputData().put("result", invocationResult);
            result.getOutputData().put("progress", 1.0);
            result.setStatus(Status.COMPLETED);
        } catch (InvocationTargetException invocationTargetException) {
            result = new TaskResult(task);

            Throwable e = invocationTargetException.getCause();
            e.printStackTrace();
            if (e instanceof NonRetryableException) {
                result.setStatus(Status.FAILED_WITH_TERMINAL_ERROR);
            } else {
                result.setStatus(Status.FAILED);
            }

            result.setReasonForIncompletion(e.getMessage());

            // TODO: this is copied from the conductor codebase, it's ugly is it really needed ?
            StringBuilder stackTrace = new StringBuilder();
            for (StackTraceElement stackTraceElement : e.getStackTrace()) {
                String className = stackTraceElement.getClassName();
                if (className.startsWith("jdk.") || className.startsWith(FactoryConductorWorker.class.getName())) {
                    break;
                }

                stackTrace.append(stackTraceElement);
                stackTrace.append("\n");
            }

            result.log(stackTrace.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    private Function<Double, Void> makeProgress(com.netflix.conductor.common.metadata.tasks.Task task) {
        return (p) -> {
            TaskResult result = new TaskResult(task);
            task.getOutputData().put("progress", p);
            taskClient.updateTask(result);
            return null;
        };
    }

    private Task<?> toICIJTask(com.netflix.conductor.common.metadata.tasks.Task task) {
        // TODO: handle user here
        Date createdAt = Date.from(Instant.ofEpochMilli(task.getScheduledTime()));
        return new Task<>(
            task.getTaskId(), task.getTaskType(), Task.State.RUNNING, 0.0f, createdAt, task.getRetryCount(), null,
            task.getInputData(), null, null
        );
    }

    public void setPollingInterval(int pollingInterval) {
        String taskName = this.getTaskDefName();
        System.out.println("Setting the polling interval for " + taskName + ", to " + pollingInterval);
        this.pollingInterval = pollingInterval;
    }

    public int getPollingInterval() {
        System.out.println("Sending the polling interval to " + this.pollingInterval);
        return this.pollingInterval;
    }
}
