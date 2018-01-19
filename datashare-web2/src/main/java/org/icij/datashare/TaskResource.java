package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.inject.Inject;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.extract.OptionsWrapper;

import java.nio.file.Path;

import static java.nio.file.Paths.get;
import static org.icij.datashare.TaskResource.TaskResponse.Result.Error;
import static org.icij.datashare.TaskResource.TaskResponse.Result.OK;

@Prefix("/task")
public class TaskResource {
    private TaskFactory taskFactory;
    private TaskManager taskManager;

    @Inject
    public TaskResource(final TaskFactory taskFactory, final TaskManager taskManager) {
        this.taskFactory = taskFactory;
        this.taskManager = taskManager;
    }

    @Post("/index/file/:filePath")
    public TaskResponse indexFile(final String filePath, final OptionsWrapper options) {
        Path path = get(filePath.replace("|", "/"));// hack : see https://github.com/CodeStory/fluent-http/pull/143

        if (!path.toFile().exists() || !path.toFile().isDirectory()) {
            return new TaskResponse(Error);
        }
        int taskId = taskManager.startTask(taskFactory.createIndexTask(filePath, options.asOptions()));
        return new TaskResponse(OK, taskId);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class TaskResponse {
        enum Result {OK, Error}

        final Result result;
        private Integer taskId;

        TaskResponse(Result result) {
            this.result = result;
        }

        TaskResponse(Result result, int taskId) {
            this.result = result;
            this.taskId = taskId;
        }
    }
}
