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
import java.util.List;
import java.util.concurrent.ExecutionException;

import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

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
    public List<TaskResponse> tasks() {
        return taskManager.getTasks().stream().map(TaskResponse::new).collect(toList());
    }

    @Get("/id/:id")
    public TaskResponse getTask(Integer id) {
        return new TaskResponse(taskManager.getTask(id));
    }

    @Post("/index/")
    public TaskResponse indexQueue(final OptionsWrapper optionsWrapper) {
        return new TaskResponse(taskManager.startTask(taskFactory.createSpewTask(optionsWrapper.asOptions())));
    }

    @Post("/index/file/:filePath")
    public List<TaskResponse> indexFile(final String filePath, final OptionsWrapper optionsWrapper) {
        TaskResponse scanResponse = scanFile(filePath, optionsWrapper);
        Options<String> options = optionsWrapper.asOptions();
        return asList(scanResponse, new TaskResponse(taskManager.startTask(taskFactory.createSpewTask(options))));
    }

    @Post("/scan/file/:filePath")
    public TaskResponse scanFile(final String filePath, final OptionsWrapper optionsWrapper) {
        Path path = get(filePath.replace("|", "/"));// hack : see https://github.com/CodeStory/fluent-http/pull/143
        Options<String> options = optionsWrapper.asOptions();
        return new TaskResponse(taskManager.startTask(taskFactory.createScanTask(path, options)));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class TaskResponse {
        enum State {RUNNING, ERROR, DONE, CANCELLED}
        private final int hash;
        private final State state;
        private final double progress;

        TaskResponse(TaskManager.MonitorableFutureTask task) {
            this.hash = task.hashCode();
            State state;
            if (task.isDone()) {
                try {
                    task.get();
                    state = State.DONE;
                } catch (ExecutionException|InterruptedException e) {
                    state = State.ERROR;
                }
                this.state = task.isCancelled() ? State.CANCELLED : state;
            } else {
                this.state = State.RUNNING;
            }
            progress = task.getProgressRate();
        }
    }
}
