package org.icij.datashare.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import net.codestory.http.Context;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.TaskPolicy;
import org.icij.datashare.policies.TaskPolicyChecker;
import org.icij.datashare.session.DatashareUser;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

public class McpStopTaskTool implements McpTool {
    // Calling TaskResource.stopTask directly skips its @TaskPolicy, so the route's own annotation is read here
    // to keep the roles defined in one place.
    private static final TaskPolicy STOP_TASK_POLICY = stopTaskRoutePolicy();
    private final TaskResource taskResource;
    private final TaskManager taskManager;
    private final TaskPolicyChecker taskPolicyChecker;

    @Inject
    public McpStopTaskTool(TaskResource taskResource, TaskManager taskManager, TaskPolicyChecker taskPolicyChecker) {
        this.taskResource = taskResource;
        this.taskManager = taskManager;
        this.taskPolicyChecker = taskPolicyChecker;
    }

    @Override
    public String name() {
        return "stop_task";
    }

    @Override
    public String description() {
        return "Stops a running task.";
    }

    @Override
    public Object call(JsonNode arguments, Context context) throws IOException {
        String taskId = McpArguments.requireText(arguments, "taskId");
        requireStopAllowed(taskId, Authorizer.requireUser((DatashareUser) context.currentUser()));
        return Map.of("taskId", taskId, "stopped", taskResource.stopTask(taskId));
    }

    private void requireStopAllowed(String taskId, DatashareUser user) throws IOException {
        if (!taskPolicyChecker.isAllowed(user, findTask(taskId), STOP_TASK_POLICY)) {
            throw new McpToolException.Forbidden(taskId);
        }
    }

    private Task<Serializable> findTask(String taskId) throws IOException {
        try {
            return taskManager.getTask(taskId);
        } catch (UnknownTask e) {
            throw new McpToolException.NotFound(taskId, e);
        }
    }

    private static TaskPolicy stopTaskRoutePolicy() {
        try {
            return TaskResource.class.getMethod("stopTask", String.class).getAnnotation(TaskPolicy.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("TaskResource.stopTask(String) is gone", e);
        }
    }
}
