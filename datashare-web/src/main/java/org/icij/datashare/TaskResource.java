package org.icij.datashare;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import org.icij.datashare.extract.OptionsWrapper;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.user.User;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import static java.lang.Boolean.parseBoolean;
import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static org.icij.datashare.text.nlp.AbstractModels.syncModels;
import static org.icij.task.Options.from;

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
    public List<TaskResponse> tasks(Context context) {
        return taskManager.getTasks().stream().filter(t -> context.currentUser().equals(t.getUser())).map(TaskResponse::new).collect(toList());
    }

    @Get("/id/:id")
    public TaskResponse getTask(String id) {
        return new TaskResponse(taskManager.getTask(id));
    }

    @Post("/index/")
    public TaskResponse indexQueue(final OptionsWrapper optionsWrapper, Context context) {
        IndexTask indexTask = taskFactory.createIndexTask((User) context.currentUser(), optionsWrapper.asOptions());
        return new TaskResponse(taskManager.startTask(indexTask));
    }

    @Post("/index/file")
    public List<TaskResponse> indexDefault(final OptionsWrapper optionsWrapper, Context context) {
        return indexFile(propertiesProvider.getProperties().getProperty("dataDir"), optionsWrapper, context);
    }

    @Post("/index/file/:filePath")
    public List<TaskResponse> indexFile(final String filePath, final OptionsWrapper optionsWrapper, Context context) {
        TaskResponse scanResponse = scanFile(filePath, optionsWrapper, context);
        Options<String> options = from(propertiesProvider.createMerged(optionsWrapper.asProperties()));
        return asList(scanResponse, new TaskResponse(taskManager.startTask(taskFactory.createIndexTask((User) context.currentUser(), options))));
    }

    @Post("/scan/file/:filePath")
    public TaskResponse scanFile(final String filePath, final OptionsWrapper optionsWrapper, Context context) {
        Path path = get("/", filePath);
        Options<String> options = from(propertiesProvider.createMerged(optionsWrapper.asProperties()));
        return new TaskResponse(taskManager.startTask(taskFactory.createScanTask((User) context.currentUser(), path, options)));
    }

    @Post("/clean/")
    public List<TaskResponse> cleanDoneTasks() {
        return taskManager.cleanDoneTasks().stream().map(TaskResponse::new).collect(toList());
    }

    @Put("/stop/:taskName")
    public boolean stopTask(final String taskName) {
        return taskManager.stopTask(notFoundIfNull(taskManager.getTask(taskName)).toString());
    }

    @Put("/stopAll")
    public Map<String, Boolean> stopAllTasks() {
        return taskManager.getTasks().stream().
                filter(t -> !t.isDone()).collect(
                        toMap(TaskManager.MonitorableFutureTask::toString, t -> taskManager.stopTask(t.toString())));
    }

    @Post("/findNames/:pipeline")
    public List<TaskResponse> extractNlp(final String pipeline, final OptionsWrapper optionsWrapper, Context context)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {

        Properties mergedProps = propertiesProvider.createMerged(optionsWrapper.asProperties());
        syncModels(parseBoolean(mergedProps.getProperty("syncModels", "true")));

        AbstractPipeline abstractPipeline = AbstractPipeline.create(pipeline, new PropertiesProvider(mergedProps));

        TaskManager.MonitorableFutureTask<Void> nlpTask = createNlpApp(pipeline, context, mergedProps, abstractPipeline);
        if (parseBoolean(mergedProps.getProperty("resume", "true"))) {
            TaskManager.MonitorableFutureTask<Integer> resumeNlpTask = taskManager.startTask(taskFactory.createResumeNlpTask((User) context.currentUser(), pipeline));
            return asList(new TaskResponse(resumeNlpTask), new TaskResponse(nlpTask));
        }
        return singletonList(new TaskResponse(nlpTask));
    }

    private TaskManager.MonitorableFutureTask<Void> createNlpApp(String pipeline, Context context, Properties mergedProps, AbstractPipeline abstractPipeline) {
        CountDownLatch latch = new CountDownLatch(1);
        TaskManager.MonitorableFutureTask<Void> task = taskManager.startTask(taskFactory.createNlpTask((User) context.currentUser(), abstractPipeline, mergedProps, latch::countDown));
        if (parseBoolean(mergedProps.getProperty("waitForNlpApp", "true"))) {
            try {
                logger.info("waiting for NlpApp {} to listen...", pipeline);
                latch.await(10, SECONDS);
                logger.info("...{} is listening", pipeline);
            } catch (InterruptedException e) {
                logger.error("NlpApp has been interrupted", e);
            }
        }
        return task;
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
                } catch (CancellationException cex) {
                    state = State.CANCELLED;
                } catch (ExecutionException|InterruptedException e) {
                    state = State.ERROR;
                }
                progress = 1;
                this.state = task.isCancelled() ? State.CANCELLED : state;
            } else {
                this.state = State.RUNNING;
                progress = task.getProgressRate();
            }
        }
    }
}
