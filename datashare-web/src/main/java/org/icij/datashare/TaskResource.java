package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import org.icij.datashare.extract.OptionsWrapper;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.user.User;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static java.lang.Boolean.parseBoolean;
import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.text.nlp.Pipeline.Type.valueOf;

@Prefix("/api/task")
public class TaskResource {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private TaskFactory taskFactory;
    private TaskManager taskManager;
    private final PropertiesProvider propertiesProvider;

    @Inject
    public TaskResource(final TaskFactory taskFactory, final TaskManager taskManager, final PropertiesProvider propertiesProvider) {
        this.taskFactory = taskFactory;
        this.taskManager = taskManager;
        this.propertiesProvider = propertiesProvider;
    }

    @Get("/")
    public List<TaskResponse> tasks() {
        return taskManager.getTasks().stream().map(TaskResponse::new).collect(toList());
    }

    @Get("/id/:id")
    public TaskResponse getTask(String id) {
        return new TaskResponse(taskManager.getTask(id));
    }

    @Post("/index/")
    public TaskResponse indexQueue(final OptionsWrapper optionsWrapper, Context context) {
        return new TaskResponse(taskManager.startTask(taskFactory.createIndexTask((User) context.currentUser(), optionsWrapper.asOptions())));
    }

    @Post("/index/file/:filePath")
    public List<TaskResponse> indexFile(final String filePath, final OptionsWrapper optionsWrapper, Context context) {
        TaskResponse scanResponse = scanFile(filePath, optionsWrapper, context);
        Options<String> options = optionsWrapper.asOptions();
        return asList(scanResponse, new TaskResponse(taskManager.startTask(taskFactory.createIndexTask((User) context.currentUser(), options))));
    }

    @Post("/scan/file/:filePath")
    public TaskResponse scanFile(final String filePath, final OptionsWrapper optionsWrapper, Context context) {
        Path path = get(filePath.replace("|", "/"));// hack : see https://github.com/CodeStory/fluent-http/pull/143
        Options<String> options = optionsWrapper.asOptions();
        return new TaskResponse(taskManager.startTask(taskFactory.createScanTask((User) context.currentUser(), path, options)));
    }

    @Post("/clean/")
    public List<TaskResponse> cleanDoneTasks() {
        return taskManager.cleanDoneTasks().stream().map(TaskResponse::new).collect(toList());
    }

    @Post("/findNames/:pipeline")
    public List<TaskResponse> extractNlp(final String pipeline, final OptionsWrapper optionsWrapper, Context context)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {
        Class<? extends AbstractPipeline> pipelineClass = (Class<? extends AbstractPipeline>) Class.forName(valueOf(pipeline).getClassName());

        Properties properties = new Properties();
        optionsWrapper.getOptions().forEach(properties::setProperty);
        Properties mergedProps = propertiesProvider.createMerged(properties);

        AbstractPipeline abstractPipeline = pipelineClass.getDeclaredConstructor(PropertiesProvider.class).newInstance(propertiesProvider);
        TaskManager.MonitorableFutureTask<Void> nlpTask = taskManager.startTask(taskFactory.createNlpTask((User) context.currentUser(), abstractPipeline, mergedProps));
        if (parseBoolean(mergedProps.getProperty("resume", "true"))) {
            TaskManager.MonitorableFutureTask<Integer> resumeNlpTask = taskManager.startTask(taskFactory.createResumeNlpTask((User) context.currentUser(), pipeline));
            return asList(new TaskResponse(resumeNlpTask), new TaskResponse(nlpTask));
        }
        return singletonList(new TaskResponse(nlpTask));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class TaskResponse {
        enum State {RUNNING, ERROR, DONE, CANCELLED}
        private final String name;
        private final State state;
        private final double progress;

        TaskResponse(TaskManager.MonitorableFutureTask task) {
            this.name = task.toString();
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
