package org.icij.datashare.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.icij.datashare.text.Project;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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
import static net.codestory.http.payload.Payload.forbidden;
import static net.codestory.http.payload.Payload.ok;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.icij.datashare.PropertiesProvider.MAP_NAME_OPTION;
import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPTION;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_DOWNLOAD_DIR;
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
    @Operation(description = "Gets all the user tasks.<br>" +
            "A filter can be added with a pattern contained in the task name.",
            parameters = {@Parameter(name = "filter", description = "pattern contained in the task name", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns the list of tasks", useReturnTypeSchema = true)
    @Get("/all")
    public List<TaskView<?>> tasks(Context context) {
        Pattern pattern = Pattern.compile(StringUtils.isEmpty(context.get("filter")) ? ".*": String.format(".*%s.*", context.get("filter")));
        return taskManager.get().stream().
                filter(t -> context.currentUser().equals(t.getUser())).
                filter(t -> pattern.matcher(t.name).matches()).
                collect(toList());
    }

    @Operation(description = "Gets one task with its id.")
    @ApiResponse(responseCode = "200", description = "returns the task from its id", useReturnTypeSchema = true)
    @Get("/:id")
    public TaskView<?> getTask(@Parameter(name = "id", description = "task id", in = ParameterIn.PATH) String id) {
        return notFoundIfNull(taskManager.get(id));
    }

    @Operation(description = "Gets task result with its id")
    @ApiResponse(responseCode = "200", description = "returns 200 and the result")
    @ApiResponse(responseCode = "204", description = "returns 204 if there is no result")
    @ApiResponse(responseCode = "403", description = "returns 403 if the task is not belonging to current user")
    @ApiResponse(responseCode = "404", description = "returns 404 if the task doesn't exist")
    @Get("/:id/result")
    public Payload getTaskResult(@Parameter(name = "id", description = "task id", in = ParameterIn.PATH) String id, Context context) {
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

    @Operation(description = "Preflight request for batch download.")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS and POST")
    @Options("/batchDownload")
    public Payload batchDownloadPreflight(final Context context) {
        return ok().withAllowMethods("OPTIONS", "POST").withAllowHeaders("Content-Type");
    }

    @Operation(description = "Download files from a search query.<br>Expected parameters are :<br>" +
            "- project: string<br>- query: string or elasticsearch JSON query<br>" +
            "If the query is a string it is taken as an ES query string, else it is a raw JSON query (without the query part)," +
            "see org.elasticsearch.index.query.WrapperQueryBuilder that is used to wrap the query",
            requestBody = @RequestBody(description = "the json used to wrap the query", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the json task", useReturnTypeSchema = true)
    @Post("/batchDownload")
    public TaskView<File> batchDownload(final OptionsWrapper<Object> optionsWrapper, Context context) throws JsonProcessingException {
        Map<String, Object> options = optionsWrapper.getOptions();
        Path downloadDir = get(propertiesProvider.getProperties().getProperty(BATCH_DOWNLOAD_DIR));
        if (!downloadDir.toFile().exists()) downloadDir.toFile().mkdirs();
        String query = options.get("query") instanceof Map ? JsonObjectMapper.MAPPER.writeValueAsString(options.get("query")): (String)options.get("query");
        String uri = (String) options.get("uri");
        boolean batchDownloadEncrypt = parseBoolean(propertiesProvider.get("batchDownloadEncrypt").orElse("false"));
        List<String> projectIds = (List<String>) options.get("projectIds");
        BatchDownload batchDownload = new BatchDownload(projectIds.stream().map(Project::project).collect(toList()), (User) context.currentUser(), query, uri, downloadDir, batchDownloadEncrypt);
        BatchDownloadRunner downloadTask = taskFactory.createDownloadRunner(batchDownload, v -> null);
        return taskManager.startTask(downloadTask, new HashMap<String, Object>() {{ put("batchDownload", batchDownload);}});
    }

    @Operation(description = "Indexes files from the queue.",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the json task", useReturnTypeSchema = true)
    @Post("/batchUpdate/index")
    public TaskView<Long> indexQueue(final OptionsWrapper<String> optionsWrapper, Context context) {
        IndexTask indexTask = taskFactory.createIndexTask((User) context.currentUser(),
                propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), optionsWrapper.asProperties());
        return taskManager.startTask(indexTask);
    }

    @Operation(description = "Indexes files in a directory (with docker, it is the mounted directory that is scanned).",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the list of tasks created", useReturnTypeSchema = true)
    @Post("/batchUpdate/index/file")
    public List<TaskView<Long>> indexDefault(final OptionsWrapper<String> optionsWrapper, Context context) throws Exception {
        return indexFile(propertiesProvider.get("dataDir").orElse("/home/datashare/data"), optionsWrapper, context);
    }

    @Operation(description = "Indexes all files of a directory with the given path.",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the list of tasks created", useReturnTypeSchema = true)
    @Post("/batchUpdate/index/:filePath:")
    public List<TaskView<Long>> indexFile(@Parameter(name = "filePath", description = "path of the directory", in = ParameterIn.PATH) final String filePath, final OptionsWrapper<String> optionsWrapper, Context context) throws Exception {
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

    @Operation(description = "Scans recursively a directory with the given path.",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the created task", useReturnTypeSchema = true)
    @Post("/batchUpdate/scan/:filePath:")
    public TaskView<Long> scanFile(@Parameter(name = "filePath", description = "path of the directory", in = ParameterIn.PATH) final String filePath, final OptionsWrapper<String> optionsWrapper, Context context) {
        Path path = IS_OS_WINDOWS ?  get(filePath) : get(File.separator, filePath);
        return taskManager.startTask(taskFactory.createScanTask((User) context.currentUser(), propertiesProvider.get(QUEUE_NAME_OPTION).orElse("extract:queue"), path,
                propertiesProvider.createOverriddenWith(optionsWrapper.getOptions())));
    }

    @Operation(description = "Cleans all DONE tasks.")
    @ApiResponse(responseCode = "200", description = "returns 200 and the list of removed tasks", useReturnTypeSchema = true)
    @Post("/clean")
    public List<TaskView<?>> cleanDoneTasks() {
        return taskManager.clearDoneTasks();
    }

    @Operation(description = "Cleans a specific task.")
    @ApiResponse(responseCode = "200", description = "returns 200 if the task is removed")
    @ApiResponse(responseCode = "403", description = "returns 403 if the task is still in RUNNING state")
    @Delete("/clean/:taskName:")
    public Payload cleanTask(@Parameter(name = "taskName", description = "name of the task to delete", in = ParameterIn.PATH) final String taskName, Context context) {
        TaskView<?> task = forbiddenIfNotSameUser(context, notFoundIfNull(taskManager.get(taskName)));
        if (task.getState() == TaskView.State.RUNNING) {
            return forbidden();
        } else {
            taskManager.clearTask(task.name);
            return ok();
        }
    }

    @Operation(description = "Preflight request for task cleaning.")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS and DELETE")
    @Options("/clean/:taskName:")
    public Payload cleanTaskPreflight(final String taskName) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    @Operation(description = "Cancels the task with the given name.")
    @ApiResponse(responseCode = "200", description = "returns 200 with the cancellation status (true/false)", useReturnTypeSchema = true)
    @Put("/stop/:taskId:")
    public boolean stopTask(@Parameter(name = "taskName", description = "name of the task to cancel", in = ParameterIn.PATH) final String taskId) {
        return taskManager.stopTask(notFoundIfNull(taskManager.get(taskId)).name);
    }

    @Operation(description = "Preflight request to stop tasks.")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS and PUT")
    @Options("/stop/:taskName:")
    public Payload stopTaskPreflight(final String taskName) {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    @Operation(description = "Cancels the running tasks. It returns a map with task name/stop statuses.<br>" +
            "If the status is false, it means that the thread has not been stopped.")
    @ApiResponse(responseCode = "200", description = "returns 200 and the tasks stop result map", useReturnTypeSchema = true)
    @Put("/stopAll")
    public Map<String, Boolean> stopAllTasks(final Context context) {
        Map<String, Boolean> collect = taskManager.get().stream().
                filter(t -> context.currentUser().equals(t.getUser())).
                filter(t -> t.getState() == TaskView.State.RUNNING).collect(
                toMap(t -> t.name, t -> taskManager.stopTask(t.name)));
        return collect;
    }

    @Operation(description = "Preflight request to stop all tasks.")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS and PUT")
    @Options("/stopAll")
    public Payload stopAllTasksPreflight() {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    @Operation(description = "Find names using the given pipeline :<br><br>" +
            "- OPENNLP<br>" +
            "- CORENLP<br>" +
            "- IXAPIPE<br>" +
            "- GATENLP<br>" +
            "- MITIE<br><br>" +
            "This endpoint is going to find all Documents that are not taggued with the given pipeline and extract named entities for all these documents.",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the created task", useReturnTypeSchema = true)
    @Post("/findNames/:pipeline")
    public List<TaskView<?>> extractNlp(@Parameter(name = "pipeline", description = "name of the NLP pipeline to use", in = ParameterIn.PATH) final String pipelineName, final OptionsWrapper<String> optionsWrapper, Context context) {
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
