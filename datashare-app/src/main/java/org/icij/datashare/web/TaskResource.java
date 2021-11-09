package org.icij.datashare.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
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
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
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
    public List<TaskView<?>> tasks(Context context) {
        Pattern pattern = Pattern.compile(StringUtils.isEmpty(context.get("filter")) ? ".*": String.format(".*%s.*", context.get("filter")));
        return taskManager.get().stream().
                filter(t -> context.currentUser().equals(t.getUser())).
                filter(t -> pattern.matcher(t.name).matches()).
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
    public TaskView<?> getTask(String id) {
        return notFoundIfNull(taskManager.get(id));
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
    public Payload getTaskResult(String id, Context context) {
        TaskView<?> task = forbiddenIfNotSameUser(context, notFoundIfNull(taskManager.get(id)));
        Object result = task.getResult();
        if (result instanceof File) {
            final Path appPath = ((File) result).isAbsolute() ?
                    get(System.getProperty("user.dir")).resolve(context.env().appFolder()) :
                    get(context.env().appFolder());
            Path resultPath = appPath.relativize(((File) result).toPath());
            return new Payload(resultPath).withHeader("Content-Disposition", "attachment;filename=\"" + resultPath.getFileName() + "\"");
        }
        return result == null ? new Payload(204) : new Payload(result);
    }

    @Options("/batchDownload")
    public Payload batchDownloadPreflight(final Context context) {
        return ok().withAllowMethods("OPTIONS", "POST").withAllowHeaders("Content-Type");
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
    public TaskView<File> batchDownload(final OptionsWrapper<Object> optionsWrapper, Context context) throws JsonProcessingException {
        Map<String, Object> options = optionsWrapper.getOptions();
        Path tmpPath = get(context.env().appFolder(), "tmp");
        if (!tmpPath.toFile().exists()) tmpPath.toFile().mkdirs();
        String query = options.get("query") instanceof Map ? JsonObjectMapper.MAPPER.writeValueAsString(options.get("query")): (String)options.get("query");
        boolean batchDownloadEncrypt = parseBoolean(propertiesProvider.get("batchDownloadEncrypt").orElse("false"));
        BatchDownload batchDownload = new BatchDownload(project((String) options.get("project")), (User) context.currentUser(), query, tmpPath, batchDownloadEncrypt);
        BatchDownloadRunner downloadTask = taskFactory.createDownloadRunner(batchDownload, v -> null);
        return taskManager.startTask(downloadTask, new HashMap<String, Object>() {{ put("batchDownload", batchDownload);}});
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
    public TaskView<Long> indexQueue(final OptionsWrapper<String> optionsWrapper, Context context) {
        IndexTask indexTask = taskFactory.createIndexTask((User) context.currentUser(),
                propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), optionsWrapper.asProperties());
        return taskManager.startTask(indexTask);
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
    public List<TaskView<Long>> indexDefault(final OptionsWrapper<String> optionsWrapper, Context context) throws Exception {
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
    public List<TaskView<Long>> indexFile(final String filePath, final OptionsWrapper<String> optionsWrapper, Context context) throws Exception {
        TaskView<Long> scanResponse = scanFile(filePath, optionsWrapper, context);
        Properties properties = propertiesProvider.createOverriddenWith(optionsWrapper.getOptions());
        User user = (User) context.currentUser();
        if (properties.get("filter") != null && Boolean.parseBoolean(properties.getProperty("filter"))) {
            String reportName = propertiesProvider.get(MAP_NAME_OPTION).orElse("extract:report");
            taskFactory.createScanIndexTask(user, reportName).call();
            properties.put(MAP_NAME_OPTION, reportName);
        }
        return asList(scanResponse, taskManager.startTask(taskFactory.createIndexTask(user, propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), properties)));
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
    public TaskView<Long> scanFile(final String filePath, final OptionsWrapper<String> optionsWrapper, Context context) {
        Path path = IS_OS_WINDOWS ?  get(filePath):get(File.separator, filePath);
        return taskManager.startTask(taskFactory.createScanTask((User) context.currentUser(), propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), path,
                propertiesProvider.createOverriddenWith(optionsWrapper.getOptions())));
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
    public List<TaskView<?>> cleanDoneTasks() {
        return taskManager.clearDoneTasks();
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
    public TaskView<?> runBatchSearches(Context context) {
        // TODO replace with call with batchId (in local mode there is only one batch run at a time)
        BatchSearchLoop batchSearchLoop = taskFactory.createBatchSearchLoop();
        batchSearchLoop.requeueDatabaseBatches();
        batchSearchLoop.enqueuePoison();

        return taskManager.startTask(batchSearchLoop::run);
    }

    /**
     * Cancels the task with the given name. It answers 200 with the cancellation status `true|false`
     *
     * @param taskId
     * @return
     */
    @Put("/stop/:taskId:")
    public boolean stopTask(final String taskId) {
        return taskManager.stopTask(notFoundIfNull(taskManager.get(taskId)).name);
    }

    @Options("/stop/:taskName:")
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
        Map<String, Boolean> collect = taskManager.get().stream().
                filter(t -> context.currentUser().equals(t.getUser())).
                filter(t -> t.getState() == TaskView.State.RUNNING).collect(
                toMap(t -> t.name, t -> taskManager.stopTask(t.name)));
        return collect;
    }

    @Options("/stopAll")
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
    public List<TaskView<?>> extractNlp(final String pipelineName, final OptionsWrapper<String> optionsWrapper, Context context) {
        Properties mergedProps = propertiesProvider.createOverriddenWith(optionsWrapper.getOptions());
        syncModels(parseBoolean(mergedProps.getProperty("syncModels", "true")));

        Pipeline pipeline = pipelineRegistry.get(Pipeline.Type.parse(pipelineName));

        TaskView<Void> nlpTask = createNlpApp(context, mergedProps, pipeline);
        if (parseBoolean(mergedProps.getProperty("resume", "true"))) {
            TaskView<Long> resumeNlpTask = taskManager.startTask(
                    taskFactory.createResumeNlpTask((User) context.currentUser(),
                            new HashSet<Pipeline.Type>() {{add(Pipeline.Type.parse(pipelineName));}}));
            return asList(resumeNlpTask, nlpTask);
        }
        return singletonList(nlpTask);
    }

    private TaskView<Void> createNlpApp(Context context, Properties mergedProps, Pipeline pipeline) {
        CountDownLatch latch = new CountDownLatch(1);
        TaskView<Void> taskView = taskManager.startTask(taskFactory.createNlpTask((User) context.currentUser(), pipeline, mergedProps, latch::countDown));
        if (parseBoolean(mergedProps.getProperty("waitForNlpApp", "true"))) {
            try {
                logger.info("waiting for NlpApp {} to listen...", pipeline);
                latch.await(10, SECONDS);
                logger.info("...{} is listening", pipeline);
            } catch (InterruptedException e) {
                logger.error("NlpApp has been interrupted", e);
            }
        }
        return taskView;
    }

    private static <V> TaskView<V> forbiddenIfNotSameUser(Context context, TaskView<V> task) {
        if (!task.getUser().equals(context.currentUser())) throw new ForbiddenException();
        return task;
    }
}
