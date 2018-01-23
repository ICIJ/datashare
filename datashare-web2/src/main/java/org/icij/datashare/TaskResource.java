package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.inject.Inject;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.extract.OptionsWrapper;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.Future;

import static java.nio.file.Paths.get;
import static org.icij.datashare.TaskResource.TaskResponse.Result.Error;
import static org.icij.datashare.TaskResource.TaskResponse.Result.OK;

@Prefix("/task")
public class TaskResource {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private TaskFactory taskFactory;
    private TaskManager taskManager;

    @Inject
    public TaskResource(final TaskFactory taskFactory, final TaskManager taskManager) {
        this.taskFactory = taskFactory;
        this.taskManager = taskManager;
    }

    @Get("/")
    public Collection<Future> tasks() {
        return taskManager.getTasks();
    }

    @Get("/id/:id")
    public Future getTask(Integer id) {
        return taskManager.getTask(id);
    }

    @Post("/index/")
    public TaskResponse indexQueue(final OptionsWrapper optionsWrapper) {
        return new TaskResponse(OK, taskManager.startTask(taskFactory.createSpewTask(optionsWrapper.asOptions())));
    }

    @Post("/index/file/:filePath")
    public TaskResponse indexFile(final String filePath, final OptionsWrapper optionsWrapper) {
        TaskResponse scanResponse = scanFile(filePath, optionsWrapper);
        if (scanResponse.result == Error) {
            return scanResponse;
        }
        Options<String> options = optionsWrapper.asOptions();
        return scanResponse.add(taskManager.startTask(taskFactory.createSpewTask(options)));
    }

    @Post("/scan/file/:filePath")
    public TaskResponse scanFile(final String filePath, final OptionsWrapper optionsWrapper) {
        Path path = get(filePath.replace("|", "/"));// hack : see https://github.com/CodeStory/fluent-http/pull/143

        if (!path.toFile().exists() || !path.toFile().isDirectory()) {
            logger.error("path {} not found or not a directory", path);
            return new TaskResponse(Error);
        }
        Options<String> options = optionsWrapper.asOptions();
        return new TaskResponse(OK, taskManager.startTask(taskFactory.createScanTask(path, options)));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class TaskResponse {
        enum Result {OK, Error;}
        final Result result;

        private Integer[] taskIds;
        TaskResponse(Result result) {
            this.result = result;
        }

        TaskResponse(Result result, Integer... taskIds) {
            this.result = result;
            this.taskIds = taskIds;
        }

        public TaskResponse add(int taskId) {
            Integer[] newTaskIds = new Integer[taskIds.length + 1];
            System.arraycopy(taskIds, 0, newTaskIds, 0, taskIds.length);
            newTaskIds[newTaskIds.length - 1] = taskId;
            return new TaskResponse(result, newTaskIds);
        }
    }
}
