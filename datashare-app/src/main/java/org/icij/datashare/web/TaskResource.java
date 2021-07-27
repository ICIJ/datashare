package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.payload.Payload;
import org.apache.commons.lang3.StringUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.OptionsWrapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.tasks.*;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import static java.lang.Boolean.parseBoolean;
import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static net.codestory.http.payload.Payload.ok;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.icij.datashare.PropertiesProvider.MAP_NAME_OPTION;
import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPTION;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.nlp.AbstractModels.syncModels;

@Singleton
@Prefix("/api/task")
public class TaskResource {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final TaskFactory taskFactory;
    private final TaskManager taskManager;
    private final PropertiesProvider propertiesProvider;
    private final PipelineRegistry pipelineRegistry;

    @Inject
    public TaskResource(final TaskFactory taskFactory, final TaskManager taskManager, final PropertiesProvider propertiesProvider, final PipelineRegistry pipelineRegistry) {
        this.taskFactory = taskFactory;
        this.taskManager = taskManager;
        this.propertiesProvider = propertiesProvider;
        this.pipelineRegistry = pipelineRegistry;
    }

    /**
     * gets all the user tasks
     * a filter can be added with a pattern contained in the task name.
     *
     * @return 200 and the list of tasks
     *
     * Example :
     * $(curl localhost:8080/api/task/all?filter=BatchDownloadRunner)
     */
    @Get("/all")
    public List<TaskResponse> tasks(Context context) {
        Pattern pattern = Pattern.compile(StringUtils.isEmpty(context.get("filter")) ? ".*": String.format(".*%s.*", context.get("filter")));
        return taskManager.getTasks().stream().
                filter(t -> context.currentUser().equals(t.getUser())).
                filter(t -> pattern.matcher(t.toString()).matches()).
                map(TaskResponse::new).
                collect(toList());
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
    @Get("/:id")
    public TaskResponse getTask(String id) {
        return new TaskResponse(notFoundIfNull(taskManager.getTask(id)));
    }

    /**
     * gets task result with its id
     *
     * @param id
     * @return 200 and the result,
     *         204 if there is no result
     *         404 if the tasks doesn't exist
     *         403 if the task is not belonging to current user
     *
     * Example :
     * $(curl localhost:8080/api/task/21148262/result)
     */
    @Get("/:id/result")
    public Payload getTaskResult(String id, Context context) throws ExecutionException, InterruptedException {
        TaskManager.MonitorableFutureTask task = forbiddenIfNotSameUser(context, notFoundIfNull(taskManager.getTask(id)));
        Object result = task.get();
        if (result instanceof File) {
            result = Paths.get(context.env().appFolder()).relativize(((File) result).toPath());
            return new Payload(result).withHeader("Content-Disposition", "attachment;filename=\"" + ((Path) result).getFileName() + "\"");
        }
        return result == null ? new Payload(204): new Payload(result);
    }

    /**
     * download files from a search query. Expected parameters are :
     *
     * * project: string
     * * query: string or elasticsearch JSON query
     *
     * if the query is a string it is taken as an ES query string, else it is a raw JSON query (without the query part)
     * @see org.elasticsearch.index.query.WrapperQueryBuilder that is used to wrap the query
     *
     * @param optionsWrapper wrapper for options json
     *
     * @return 200 and json task
     *
     * Example :
     * $(curl -XPOST -H 'Content-Type: application/json' localhost:8080/api/task/batchDownload -d '{"options": {"project":"genapi-datashare", "query": "*" }}')
     */
    @Post("/batchDownload")
    public TaskResponse batchDownload(final OptionsWrapper<Object> optionsWrapper, Context context) throws JsonProcessingException {
        Map<String, Object> options = optionsWrapper.getOptions();
        Path tmpPath = get(context.env().appFolder(), "tmp");
        if (!tmpPath.toFile().exists()) tmpPath.toFile().mkdirs();
        String query = options.get("query") instanceof Map ? JsonObjectMapper.MAPPER.writeValueAsString(options.get("query")): (String)options.get("query");
        BatchDownload batchDownload = new BatchDownload(project((String) options.get("project")), (User) context.currentUser(), query, tmpPath);
        BatchDownloadRunner downloadTask = taskFactory.createDownloadRunner(batchDownload);
        return new TaskResponse(taskManager.startTask(downloadTask, new HashMap<String, Object>() {{ put("batchDownload", batchDownload);}}));
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
    public TaskResponse indexQueue(final OptionsWrapper<String> optionsWrapper, Context context) {
        IndexTask indexTask = taskFactory.createIndexTask((User) context.currentUser(),
                propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), optionsWrapper.asProperties());
        return new TaskResponse(taskManager.startTask(indexTask));
    }

    /**
     * Indexes files in a directory (with docker, it is the mounted directory that is scanned)
     *
     * @param optionsWrapper
     * @return 200 and the list of tasks created
     *
     * Example :
     * $(curl -XPOST localhost:8080/api/task/batchUpdate/index/file -d '{}')
     */
    @Post("/batchUpdate/index/file")
    public List<TaskResponse> indexDefault(final OptionsWrapper<String> optionsWrapper, Context context) throws Exception {
        return indexFile(propertiesProvider.get("dataDir").orElse("/home/datashare/data"), optionsWrapper, context);
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
    public List<TaskResponse> indexFile(final String filePath, final OptionsWrapper<String> optionsWrapper, Context context) throws Exception {
        TaskResponse scanResponse = scanFile(filePath, optionsWrapper, context);
        Properties properties = propertiesProvider.createOverriddenWith(optionsWrapper.getOptions());
        User user = (User) context.currentUser();
        if (properties.get("filter") != null && Boolean.parseBoolean(properties.getProperty("filter"))) {
            String reportName = propertiesProvider.get(MAP_NAME_OPTION).orElse("extract:report");
            taskFactory.createScanIndexTask(user, reportName).call();
            properties.put(MAP_NAME_OPTION, reportName);
        }
        return asList(scanResponse, new TaskResponse(taskManager.startTask(taskFactory.createIndexTask(user, propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), properties))));
    }

    /**
     * Scans recursively a directory with the given path
     *
     * @param filePath
     * @param optionsWrapper
     * @return 200 and the task created
     *
     * Example :
     * $(mkdir -p /tmp/apigen)
     * $(curl -XPOST localhost:8080/api/task/batchUpdate/index/tmp/apigen -d '{}')
     */
    @Post("/batchUpdate/scan/:filePath:")
    public TaskResponse scanFile(final String filePath, final OptionsWrapper<String> optionsWrapper, Context context) {
        Path path = IS_OS_WINDOWS ?  get(filePath):get(File.separator, filePath);
        return new TaskResponse(taskManager.startTask(taskFactory.createScanTask((User) context.currentUser(), propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), path,
                propertiesProvider.createOverriddenWith(optionsWrapper.getOptions()))));
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
        // TODO replace with call with batchId (in local mode there is only one batch run at a time)
        BatchSearchLoop batchSearchLoop = taskFactory.createBatchSearchLoop();
        batchSearchLoop.requeueDatabaseBatches();
        batchSearchLoop.enqueuePoison();

        return new TaskResponse(taskManager.startTask(batchSearchLoop::run));
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
     * @param pipelineName
     * @param optionsWrapper
     * @return 200 and the list of created tasks
     *
     * Example :
     * $(curl -XPOST http://dsenv:8080/api/task/findNames/CORENLP -d {})
     */
    @Post("/findNames/:pipeline")
    public List<TaskResponse> extractNlp(final String pipelineName, final OptionsWrapper<String> optionsWrapper, Context context) {
        Properties mergedProps = propertiesProvider.createOverriddenWith(optionsWrapper.getOptions());
        syncModels(parseBoolean(mergedProps.getProperty("syncModels", "true")));

        Pipeline pipeline = pipelineRegistry.get(Pipeline.Type.parse(pipelineName));

        TaskManager.MonitorableFutureTask<Void> nlpTask = createNlpApp(context, mergedProps, pipeline);
        if (parseBoolean(mergedProps.getProperty("resume", "true"))) {
            TaskManager.MonitorableFutureTask<Long> resumeNlpTask = taskManager.startTask(
                    taskFactory.createResumeNlpTask((User) context.currentUser(),
                            new HashSet<Pipeline.Type>() {{add(Pipeline.Type.parse(pipelineName));}}));
            return asList(new TaskResponse(resumeNlpTask), new TaskResponse(nlpTask));
        }
        return singletonList(new TaskResponse(nlpTask));
    }

    private TaskManager.MonitorableFutureTask<Void> createNlpApp(Context context, Properties mergedProps, Pipeline pipeline) {
        CountDownLatch latch = new CountDownLatch(1);
        TaskManager.MonitorableFutureTask<Void> task = taskManager.startTask(taskFactory.createNlpTask((User) context.currentUser(), pipeline, mergedProps, latch::countDown));
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

    private static TaskManager.MonitorableFutureTask forbiddenIfNotSameUser(Context context, TaskManager.MonitorableFutureTask task) {
        if (!task.getUser().equals(context.currentUser())) throw new ForbiddenException();
        return task;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class TaskResponse {
        private final Map<String, Object> properties;

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
            this.properties = task.properties.isEmpty() ? null: task.properties;
        }
    }
}
