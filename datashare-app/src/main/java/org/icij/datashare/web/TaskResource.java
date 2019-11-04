package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.OptionsWrapper;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.HashSet;
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
import static net.codestory.http.payload.Payload.ok;
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

    /**
     * gets all the user tasks
     *
     * @return 200 and the list of tasks
     *
     * Example :
     * $(curl localhost:8080/api/task/all)
     */
    @Get("/all")
    public List<TaskResponse> tasks(Context context) {
        return taskManager.getTasks().stream().filter(t -> context.currentUser().equals(t.getUser())).map(TaskResponse::new).collect(toList());
    }

    /**
     * gets one task with its id
     *
     * @param id
     * @return 200
     *
     * Example :
     * $(curl localhost:8080/api/task/21148262)
     */
    @Get("/:name")
    public TaskResponse getTask(String id) {
        return new TaskResponse(taskManager.getTask(id));
    }


    /**
     * index files from the queue
     *
     * @param optionsWrapper wrapper for options json
     * @return 200 and json task
     *
     * Example :
     * $(curl -XPOST localhost:8080/api/task/batchUpdate/index -d '{}')
     */
    @Post("/batchUpdate/index")
    public TaskResponse indexQueue(final OptionsWrapper optionsWrapper, Context context) {
        IndexTask indexTask = taskFactory.createIndexTask((User) context.currentUser(), optionsWrapper.asOptions());
        return new TaskResponse(taskManager.startTask(indexTask));
    }

    /**
     * Indexes files in a directory (with docker, it is the mounted directory that is scanned)
     *
     * @param optionsWrapper
     * @return 200 and the list of tasks created
     *
     * Example :
     * $(curl -XPOST localhost:8080/api/task/batchUpdate/index/file)
     */
    @Post("/batchUpdate/index/file")
    public List<TaskResponse> indexDefault(final OptionsWrapper optionsWrapper, Context context) {
        return indexFile(propertiesProvider.getProperties().getProperty("dataDir"), optionsWrapper, context);
    }

    /**
     * Indexes all files of a directory with the given path.
     *
     * @param filePath
     * @param optionsWrapper
     * @return 200 and the list of created tasks
     *
     * Example $(curl -XPOST localhost:8080/api/task/batchUpdate/index/home/dev/myfile.txt)
     */
    @Post("/batchUpdate/index/:filePath:")
    public List<TaskResponse> indexFile(final String filePath, final OptionsWrapper optionsWrapper, Context context) {
        TaskResponse scanResponse = scanFile(filePath, optionsWrapper, context);
        Options<String> options = from(propertiesProvider.createMerged(optionsWrapper.asProperties()));
        return asList(scanResponse, new TaskResponse(taskManager.startTask(taskFactory.createIndexTask((User) context.currentUser(), options))));
    }

    /**
     * Scans recursively a directory with the given path
     *
     * @param filePath
     * @param optionsWrapper
     * @return 200 and the task created
     *
     * Example :
     * $(curl -XPOST localhost:8080/api/task/batchUpdate/index/home/dev/mydir)
     */
    @Post("/batchUpdate/scan/:filePath:")
    public TaskResponse scanFile(final String filePath, final OptionsWrapper optionsWrapper, Context context) {
        Path path = get("/", filePath);
        Options<String> options = from(propertiesProvider.createMerged(optionsWrapper.asProperties()));
        return new TaskResponse(taskManager.startTask(taskFactory.createScanTask((User) context.currentUser(), path, options)));
    }

    /**
     * Cleans all DONE tasks.
     *
     * @return 200 and the list of removed tasks
     *
     * Example :
     * $(curl -XPOST -d '{}' http://dsenv:8080/api/task/clean/
     */
    @Post("/clean")
    public List<TaskResponse> cleanDoneTasks() {
        return taskManager.cleanDoneTasks().stream().map(TaskResponse::new).collect(toList());
    }

    /**
     * Run batch searches
     *
     * @return 200 and the created task
     *
     * Example :
     * $(curl -XPOST localhost:8080/api/task/batchSearch)
     */
    @Post("/batchSearch")
    public TaskResponse runBatchSearches(Context context) {
        return new TaskResponse(taskManager.startTask(taskFactory.createBatchSearchRunner((User)context.currentUser())));
    }

    /**
     * Cancels the task with the given name. It answers 200 with the cancellation status `true|false`
     *
     * @param taskId
     * @return
     */
    @Put("/stop/:taskId:")
    public boolean stopTask(final String taskId) {
        return taskManager.stopTask(notFoundIfNull(taskManager.getTask(taskId)).toString());
    }

    @net.codestory.http.annotations.Options("/stop/:taskName:")
    public Payload stopTaskPreflight(final String taskName) {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    /**
     * Cancels the running tasks. It returns a map with task name/stop statuses.
     * If the status is false, it means that the thread has not been stopped.
     *
     * @return 200 and the tasks stop result map
     *
     * Example :
     * curl -XPUT localhost:8080/api/task/stopAll
     */
    @Put("/stopAll")
    public Map<String, Boolean> stopAllTasks(final Context context) {
        return taskManager.getTasks().stream().
                filter(t -> context.currentUser().equals(t.getUser())).
                filter(t -> !t.isDone()).collect(
                        toMap(TaskManager.MonitorableFutureTask::toString, t -> taskManager.stopTask(t.toString())));
    }

    @net.codestory.http.annotations.Options("/stopAll")
    public Payload stopAllTasksPreflight() {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    /**
     * Find names using the given pipeline :
     *
     * - OPENNLP
     * - CORENLP
     * - IXAPIPE
     * - GATENLP
     * - MITIE
     *
     * This endpoint is going to find all Documents that are not taggued with the given pipeline,
     * and extract named entities for all these documents.
     *
     * @param pipeline
     * @param optionsWrapper
     * @return 200 and the list of created tasks
     *
     * Example :
     * $(curl -XPOST http://dsenv:8080/api/task/findNames/CORENLP)
     */
    @Post("/findNames/:pipeline")
    public List<TaskResponse> extractNlp(final String pipeline, final OptionsWrapper optionsWrapper, Context context)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException, NoSuchMethodException, InvocationTargetException {

        Properties mergedProps = propertiesProvider.createMerged(optionsWrapper.asProperties());
        syncModels(parseBoolean(mergedProps.getProperty("syncModels", "true")));

        AbstractPipeline abstractPipeline = AbstractPipeline.create(pipeline, new PropertiesProvider(mergedProps));

        TaskManager.MonitorableFutureTask<Void> nlpTask = createNlpApp(pipeline, context, mergedProps, abstractPipeline);
        if (parseBoolean(mergedProps.getProperty("resume", "true"))) {
            TaskManager.MonitorableFutureTask<Long> resumeNlpTask = taskManager.startTask(
                    taskFactory.createResumeNlpTask((User) context.currentUser(),
                            new HashSet<Pipeline.Type>() {{add(Pipeline.Type.parse(pipeline));}}));
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
