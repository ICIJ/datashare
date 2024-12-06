package org.icij.datashare.web;

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
import net.codestory.http.annotations.Delete;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.HttpException;
import net.codestory.http.payload.Payload;
import org.apache.commons.lang3.StringUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.extract.OptionsWrapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.tasks.BatchDownloadRunner;
import org.icij.datashare.tasks.DatashareTask;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.DatashareTaskManager;
import org.icij.datashare.tasks.EnqueueFromIndexTask;
import org.icij.datashare.tasks.ExtractNlpTask;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.ScanIndexTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

import static java.lang.Boolean.parseBoolean;
import static java.nio.file.Paths.get;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static net.codestory.http.payload.Payload.forbidden;
import static net.codestory.http.payload.Payload.ok;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPTION;
import static org.icij.datashare.PropertiesProvider.DIGEST_PROJECT_NAME_OPTION;
import static org.icij.datashare.PropertiesProvider.MAP_NAME_OPTION;
import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPTION;
import static org.icij.datashare.PropertiesProvider.RESUME_OPTION;
import static org.icij.datashare.PropertiesProvider.SYNC_MODELS_OPTION;
import static org.icij.datashare.PropertiesProvider.propertiesToMap;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_DOWNLOAD_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DATA_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.text.nlp.AbstractModels.syncModels;

@Singleton
@Prefix("/api/task")
public class TaskResource {
    private final DatashareTaskFactory taskFactory;
    private final DatashareTaskManager taskManager;
    private final PropertiesProvider propertiesProvider;

    @Inject
    public TaskResource(final  DatashareTaskFactory taskFactory, final DatashareTaskManager taskManager, final PropertiesProvider propertiesProvider) {
        this.taskFactory = taskFactory;
        this.taskManager = taskManager;
        this.propertiesProvider = propertiesProvider;
    }
    @Operation(description = "Gets all the user tasks.<br>" +
            "A filter can be added with a pattern contained in the task name.",
            parameters = {@Parameter(name = "filter", description = "pattern contained in the task name", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns the list of tasks", useReturnTypeSchema = true)
    @Get("/all")
    public List< Task<?>> tasks(Context context) throws IOException {
        Pattern pattern = Pattern.compile(StringUtils.isEmpty(context.get("filter")) ? ".*": String.format(".*%s.*", context.get("filter")));
        return taskManager.getTasks((User) context.currentUser(), pattern);
    }

    @Operation(description = "Gets one task with its id.")
    @ApiResponse(responseCode = "200", description = "returns the task from its id", useReturnTypeSchema = true)
    @Get("/:id")
    public Task<?> getTask(@Parameter(name = "id", description = "task id", in = ParameterIn.PATH) String id) throws IOException {
        return notFoundIfNull(taskManager.getTask(id));
    }

    @Operation(description = "Create a task with JSON body",
            requestBody = @RequestBody(description = "the task creation body", required = true,  content = @Content(schema = @Schema(implementation = Task.class))))
    @ApiResponse(responseCode = "201", description = "the task has been created", content = @Content(schema = @Schema(implementation = TaskResponse.class)))
    @ApiResponse(responseCode = "200", description = "the task was already existing")
    @ApiResponse(responseCode = "400", description = "bad request, for example the task payload id is not the same as the url id", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @Put("/:id?group=:group")
    public <V> Payload createTask(
        @Parameter(name = "id", description = "task id", required = true, in = ParameterIn.PATH) String id,
        @Parameter(name = "group", description = "group id", in = ParameterIn.QUERY) String group,
        Task<V> taskView
    ) throws IOException {
        if (taskView == null || id == null || !Objects.equals(taskView.id, id)) {
            return new JsonPayload(400, new ErrorResponse("body should contain a taskView, URL id should be present and equal to body id"));
        }
        return ofNullable(taskManager.startTask(taskView, new Group(group))).map(sid -> new JsonPayload(201, new TaskResponse(sid))).orElse(new JsonPayload(200));
    }

    @Operation(description = "Gets task result with its id")
    @ApiResponse(responseCode = "200", description = "returns 200 and the result")
    @ApiResponse(responseCode = "204", description = "returns 204 if there is no result")
    @ApiResponse(responseCode = "403", description = "returns 403 if the task is not belonging to current user")
    @ApiResponse(responseCode = "404", description = "returns 404 if the task doesn't exist")
    @Get("/:id/result")
    public Payload getTaskResult(@Parameter(name = "id", description = "task id", in = ParameterIn.PATH) String id, Context context) throws IOException {
         Task<?> task = forbiddenIfNotSameUser(context, ( Task<?>) notFoundIfNull(taskManager.getTask(id)));
        Object result = task.getResult();
        if (result instanceof UriResult uriResult) {
            Path filePath = Path.of(uriResult.uri().getPath());
            String fileName = filePath.getFileName().toString();
            String contentDisposition = "attachment;filename=\"" + fileName + "\"";
            InputStream fileInputStream = Files.newInputStream(filePath);
            return new Payload(fileInputStream).withHeader("Content-Disposition", contentDisposition);
        }
        return result == null ? new Payload(204) : new Payload(result);
    }

    @Operation(description = "Preflight request for batch download.")
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS and POST")
    @Options("/batchDownload")
    public Payload batchDownloadPreflight(final Context context) {
        return ok().withAllowMethods("OPTIONS", "POST").withAllowHeaders("Content-Type");
    }

    @Operation(description = "Download files from a search query.<br>Expected parameters are :<br>" +
            "- project: string<br>- query: string or elasticsearch JSON query<br>" +
            "If the query is a string it is taken as an ES query string, else it is a raw JSON query (without the query part)," +
            "see org.elasticsearch.index.query.WrapperQueryBuilder that is used to wrap the query",
            requestBody = @RequestBody(description = "the json used to wrap the query", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the json task id", useReturnTypeSchema = true)
    @Post("/batchDownload")
    public TaskResponse batchDownload(final OptionsWrapper<Object> optionsWrapper, Context context) throws Exception {
        Map<String, Object> options = optionsWrapper.getOptions();
        Properties properties = applyProjectProperties(optionsWrapper);
        Path downloadDir = get(properties.getProperty(BATCH_DOWNLOAD_DIR_OPT));
        if (!downloadDir.toFile().exists()) downloadDir.toFile().mkdirs();
        String query = options.get("query") instanceof Map ? JsonObjectMapper.MAPPER.writeValueAsString(options.get("query")): (String)options.get("query");
        String uri = (String) options.get("uri");
        boolean batchDownloadEncrypt = parseBoolean(properties.getOrDefault("batchDownloadEncrypt", "false").toString());
        List<String> projectIds = (List<String>) options.get("projectIds");

        BatchDownload batchDownload = new BatchDownload(projectIds.stream().map(Project::project).collect(toList()), (User) context.currentUser(), query, uri, downloadDir, batchDownloadEncrypt);

        return new TaskResponse(taskManager.startTask(BatchDownloadRunner.class, (User) context.currentUser(), Map.of("batchDownload", batchDownload)));
    }

    @Operation(description = "Indexes files from the queue.",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the json task id", content = @Content(schema = @Schema(implementation = TaskResponse.class)))
    @ApiResponse(responseCode = "500", description = "returns an error when stat task fails", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @Post("/batchUpdate/index")
    public Payload indexQueue(final OptionsWrapper<String> optionsWrapper, Context context) throws IOException {
        Properties properties = applyProjectProperties(optionsWrapper);
        return ofNullable(taskManager.startTask(IndexTask.class, (User) context.currentUser(), propertiesToMap(properties)))
                .map(id -> new Payload("application/json", String.format("{\"taskId\":\"%s\"}", id), 200))
                .orElse(new Payload("application/json", "{\"message\":\"unknown error\"}", 500));
    }

    @Operation(description = "Indexes files in a directory (with docker, it is the mounted directory that is scanned).",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the list of tasks created", useReturnTypeSchema = true)
    @Post("/batchUpdate/index/file")
    public Payload indexDefault(final OptionsWrapper<String> optionsWrapper, Context context) throws Exception {
        return indexFile(propertiesProvider.get("dataDir").orElse("/home/datashare/data"), optionsWrapper, context);
    }

    @Operation(description = "Indexes all files of a directory with the given path.",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the list of tasks created", content = @Content(schema = @Schema(implementation = TasksResponse.class)))
    @Post("/batchUpdate/index/:filePath:")
    public Payload indexFile(@Parameter(name = "filePath", description = "path of the directory", in = ParameterIn.PATH) final String filePath, final OptionsWrapper<String> optionsWrapper, Context context) throws Exception {
        TaskResponse scanResponse = scanFile(filePath, optionsWrapper, context);
        List<String> taskIds = new LinkedList<>();
        taskIds.add(scanResponse.taskId);
        Properties properties = applyProjectProperties(optionsWrapper);
        User user = (User) context.currentUser();
         Task<Long> scanIndex;
        // Use a report map only if the request's body contains a "filter" attribute
        if (properties.get("filter") != null && Boolean.parseBoolean(properties.getProperty("filter"))) {
            // TODO remove taskFactory.createScanIndexTask would allow to get rid of taskfactory dependency in taskresource
            // problem for now is that if we call taskManager.startTask(ScanIndexTask.class.getName(), user, propertiesToMap(properties))
            // the task will be run as a background task that will have race conditions with indexTask report loading
            scanIndex = DatashareTask.task(ScanIndexTask.class.getName(), user, propertiesToMap(properties));
            taskFactory.createScanIndexTask(scanIndex, (p) -> null).call();
            taskIds.add(scanIndex.id);
        } else {
            properties.remove(MAP_NAME_OPTION); // avoid use of reportMap to override ES docs
        }
        ofNullable(taskManager.startTask(IndexTask.class, user, propertiesToMap(properties))).ifPresent(taskIds::add);
        return new JsonPayload(new TasksResponse(taskIds));
    }

    @Operation(description = "Scans recursively a directory with the given path.",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the created task", content = @Content(schema = @Schema(implementation = TaskResponse.class)))
    @ApiResponse(responseCode = "500", description = "returns 500 if startTask fails", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @Post("/batchUpdate/scan/:filePath:")
    public TaskResponse scanFile(@Parameter(name = "filePath", description = "path of the directory", in = ParameterIn.PATH) final String filePath, final OptionsWrapper<String> optionsWrapper, Context context) throws IOException {
        Path path = IS_OS_WINDOWS ?  get(filePath) : get(File.separator, filePath);
        Properties properties = applyProjectProperties(optionsWrapper);
        properties.setProperty(DATA_DIR_OPT, path.toString());
        return ofNullable(taskManager.startTask(ScanTask.class, (User) context.currentUser(), propertiesToMap(properties)))
                .map(TaskResponse::new).orElseThrow(() -> new HttpException(500));
    }

    @Operation(description = "Cleans all DONE tasks.")
    @ApiResponse(responseCode = "200", description = "returns 200 and the list of removed tasks", useReturnTypeSchema = true)
    @Post("/clean")
    public List<Task<?>> cleanDoneTasks() throws IOException {
        return taskManager.clearDoneTasks();
    }

    @Operation(description = "Cleans a specific task.")
    @ApiResponse(responseCode = "200", description = "returns 200 if the task is removed")
    @ApiResponse(responseCode = "403", description = "returns 403 if the task is still in RUNNING state")
    @Delete("/clean/:taskName:")
    public Payload cleanTask(@Parameter(name = "taskName", description = "name of the task to delete", in = ParameterIn.PATH) final String taskId, Context context) throws IOException {
         Task<?> task = forbiddenIfNotSameUser(context, notFoundIfNull(( Task<?>) taskManager.getTask(taskId)));
        if (task.getState() == Task.State.RUNNING) {
            return forbidden();
        } else {
            taskManager.clearTask(task.id);
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
    public boolean stopTask(@Parameter(name = "taskName", description = "name of the task to cancel", in = ParameterIn.PATH) final String taskId) throws IOException {
        return taskManager.stopTask(notFoundIfNull(taskManager.getTask(taskId)).id);
    }

    @Operation(description = "Preflight request to stop tasks.")
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS and PUT")
    @Options("/stop/:taskName:")
    public Payload stopTaskPreflight(final String taskName) {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    @Operation(description = "Cancels the running tasks. It returns a map with task name/stop statuses.<br>" +
            "If the status is false, it means that the thread has not been stopped.")
    @ApiResponse(responseCode = "200", description = "returns 200 and the tasks stop result map", useReturnTypeSchema = true)
    @Put("/stopAll")
    public Map<String, Boolean> stopAllTasks(final Context context) throws IOException {
        return taskManager.stopAllTasks((User) context.currentUser());
    }

    @Operation(description = "Preflight request to stop all tasks.")
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS and PUT")
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
    @ApiResponse(responseCode = "200", description = "returns 200 and the created task ids", content = @Content(schema = @Schema(implementation = TasksResponse.class)))
    @Post("/findNames/:pipeline")
    public Payload extractNlp(@Parameter(name = "pipeline", description = "name of the NLP pipeline to use", in = ParameterIn.PATH) final String pipelineName, final OptionsWrapper<String> optionsWrapper, Context context) throws IOException {
        Properties properties = applyProjectProperties(optionsWrapper);
        properties.put(NLP_PIPELINE_OPT, pipelineName);
        syncModels(parseBoolean(properties.getProperty(SYNC_MODELS_OPTION, "true")));
        List<String> tasks = new LinkedList<>();
        if (parseBoolean(properties.getProperty(RESUME_OPTION, "true"))) {
            tasks.add(taskManager.startTask(EnqueueFromIndexTask.class, ((User) context.currentUser()), propertiesToMap(properties)));
        }
        tasks.add(taskManager.startTask(ExtractNlpTask.class, (User) context.currentUser(), propertiesToMap(properties)));
        return new JsonPayload(new TasksResponse(tasks));
    }

    public Properties applyProjectProperties(OptionsWrapper optionsWrapper) {
        Properties properties = propertiesProvider.createOverriddenWith(optionsWrapper.getOptions());
        return TaskResource.applyProjectTo(properties);
    }

    public static Properties applyProjectTo(Properties properties) {
        Properties clone = (Properties) properties.clone();
        clone.setProperty(QUEUE_NAME_OPTION, "extract:queue"); // Override any given queue name value
        clone.setProperty(MAP_NAME_OPTION, getReportMapNameFor(properties));
        clone.setProperty(DIGEST_PROJECT_NAME_OPTION, clone.getProperty(DEFAULT_PROJECT_OPTION, "local-datashare"));
        return new PropertiesProvider(clone).overrideQueueNameWithHash().getProperties();
    }

    public static String getReportMapNameFor(Properties properties) {
        String projectName = properties.getOrDefault(DEFAULT_PROJECT_OPTION, "local-datashare").toString();
        return "extract:report:" + projectName;
    }

    private static <V>  Task<V> forbiddenIfNotSameUser(Context context,  Task<V> task) {
        if (!DatashareTask.getUser(task).equals(context.currentUser())) throw new ForbiddenException();
        return task;
    }

    // JSON responses
    public record ErrorResponse(String message) {}
    public record TaskResponse(String taskId) {}
    public record TasksResponse(List<String> taskIds) {}
}
