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
import net.codestory.http.payload.Payload;
import org.apache.commons.lang3.StringUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.extract.OptionsWrapper;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.tasks.*;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static java.lang.Boolean.parseBoolean;
import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
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
    private final TaskFactory taskFactory;
    private final TaskManager taskManager;
    private final PropertiesProvider propertiesProvider;

    @Inject
    public TaskResource(final TaskFactory taskFactory, final TaskManager taskManager, final PropertiesProvider propertiesProvider) {
        this.taskFactory = taskFactory;
        this.taskManager = taskManager;
        this.propertiesProvider = propertiesProvider;
    }
    @Operation(description = "Gets all the user tasks.<br>" +
            "A filter can be added with a pattern contained in the task name.",
            parameters = {@Parameter(name = "filter", description = "pattern contained in the task name", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns the list of tasks", useReturnTypeSchema = true)
    @Get("/all")
    public List<TaskView<?>> tasks(Context context) {
        Pattern pattern = Pattern.compile(StringUtils.isEmpty(context.get("filter")) ? ".*": String.format(".*%s.*", context.get("filter")));
        return taskManager.getTasks((User) context.currentUser(), pattern);
    }

    @Operation(description = "Gets one task with its id.")
    @ApiResponse(responseCode = "200", description = "returns the task from its id", useReturnTypeSchema = true)
    @Get("/:id")
    public TaskView<?> getTask(@Parameter(name = "id", description = "task id", in = ParameterIn.PATH) String id) {
        return notFoundIfNull(taskManager.getTask(id));
    }

    @Operation(description = "Gets task result with its id")
    @ApiResponse(responseCode = "200", description = "returns 200 and the result")
    @ApiResponse(responseCode = "204", description = "returns 204 if there is no result")
    @ApiResponse(responseCode = "403", description = "returns 403 if the task is not belonging to current user")
    @ApiResponse(responseCode = "404", description = "returns 404 if the task doesn't exist")
    @Get("/:id/result")
    public Payload getTaskResult(@Parameter(name = "id", description = "task id", in = ParameterIn.PATH) String id, Context context) throws IOException {
        TaskView<?> task = forbiddenIfNotSameUser(context, notFoundIfNull(taskManager.getTask(id)));
        Object result = task.getResult();
        if (result instanceof UriResult) {
            UriResult uriResult = (UriResult) result;
            Path filePath = Path.of(uriResult.uri.getPath());
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
    @ApiResponse(responseCode = "200", description = "returns 200 and the json task", useReturnTypeSchema = true)
    @Post("/batchDownload")
    public TaskView<File> batchDownload(final OptionsWrapper<Object> optionsWrapper, Context context) throws Exception {
        Map<String, Object> options = optionsWrapper.getOptions();
        Properties properties = applyProjectProperties(optionsWrapper);
        Path downloadDir = get(properties.getProperty(BATCH_DOWNLOAD_DIR_OPT));
        if (!downloadDir.toFile().exists()) downloadDir.toFile().mkdirs();
        String query = options.get("query") instanceof Map ? JsonObjectMapper.MAPPER.writeValueAsString(options.get("query")): (String)options.get("query");
        String uri = (String) options.get("uri");
        boolean batchDownloadEncrypt = parseBoolean(properties.getOrDefault("batchDownloadEncrypt", "false").toString());
        List<String> projectIds = (List<String>) options.get("projectIds");
        BatchDownload batchDownload = new BatchDownload(projectIds.stream().map(Project::project).collect(toList()), (User) context.currentUser(), query, uri, downloadDir, batchDownloadEncrypt);
        return taskManager.startTask(BatchDownloadRunner.class.getName(), (User) context.currentUser(), new HashMap<>() {{
            put("batchDownload", batchDownload);
        }});
    }

    @Operation(description = "Indexes files from the queue.",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the json task", useReturnTypeSchema = true)
    @Post("/batchUpdate/index")
    public TaskView<Long> indexQueue(final OptionsWrapper<String> optionsWrapper, Context context) throws IOException {
        Properties properties = applyProjectProperties(optionsWrapper);
        return taskManager.startTask(IndexTask.class.getName(), (User) context.currentUser(), propertiesToMap(properties));
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
        Properties properties = applyProjectProperties(optionsWrapper);
        User user = (User) context.currentUser();
        // Use a report map only if the request's body contains a "filter" attribute
        if (properties.get("filter") != null && Boolean.parseBoolean(properties.getProperty("filter"))) {
            // TODO remove taskFactory.createScanIndexTask would allow to get rid of taskfactory dependency in taskresource
            // problem for now is that if we call taskManager.startTask(ScanIndexTask.class.getName(), user, propertiesToMap(properties))
            // the task will be run as a background task that will have race conditions with indexTask report loading
            taskFactory.createScanIndexTask(new TaskView<>(ScanIndexTask.class.getName(), user, propertiesToMap(properties)), (s, p) -> null).call();
        } else {
            properties.remove(MAP_NAME_OPTION); // avoid use of reportMap to override ES docs
        }
        return asList(scanResponse, taskManager.startTask(IndexTask.class.getName(), user, propertiesToMap(properties)));
    }

    @Operation(description = "Scans recursively a directory with the given path.",
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the created task", useReturnTypeSchema = true)
    @Post("/batchUpdate/scan/:filePath:")
    public TaskView<Long> scanFile(@Parameter(name = "filePath", description = "path of the directory", in = ParameterIn.PATH) final String filePath, final OptionsWrapper<String> optionsWrapper, Context context) throws IOException {
        Path path = IS_OS_WINDOWS ?  get(filePath) : get(File.separator, filePath);
        Properties properties = applyProjectProperties(optionsWrapper);
        properties.setProperty(DATA_DIR_OPT, path.toString());
        return taskManager.startTask(ScanTask.class.getName(), (User) context.currentUser(), propertiesToMap(properties));

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
    public Payload cleanTask(@Parameter(name = "taskName", description = "name of the task to delete", in = ParameterIn.PATH) final String taskId, Context context) {
        TaskView<?> task = forbiddenIfNotSameUser(context, notFoundIfNull(taskManager.getTask(taskId)));
        if (task.getState() == TaskView.State.RUNNING) {
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
    public boolean stopTask(@Parameter(name = "taskName", description = "name of the task to cancel", in = ParameterIn.PATH) final String taskId) {
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
    public Map<String, Boolean> stopAllTasks(final Context context) {
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
    @ApiResponse(responseCode = "200", description = "returns 200 and the created task", useReturnTypeSchema = true)
    @Post("/findNames/:pipeline")
    public List<TaskView<?>> extractNlp(@Parameter(name = "pipeline", description = "name of the NLP pipeline to use", in = ParameterIn.PATH) final String pipelineName, final OptionsWrapper<String> optionsWrapper, Context context) throws IOException {
        Properties properties = applyProjectProperties(optionsWrapper);
        properties.put(NLP_PIPELINE_OPT, pipelineName);
        syncModels(parseBoolean(properties.getProperty(SYNC_MODELS_OPTION, "true")));
        TaskView<Long> nlpTask = taskManager.startTask(ExtractNlpTask.class.getName(), (User) context.currentUser(), propertiesToMap(properties));
        if (parseBoolean(properties.getProperty(RESUME_OPTION, "true"))) {
            TaskView<Long> resumeNlpTask = taskManager.startTask(EnqueueFromIndexTask.class.getName(), ((User) context.currentUser()), propertiesToMap(properties));
            return asList(resumeNlpTask, nlpTask);
        }
        return singletonList(nlpTask);
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

    private static <V> TaskView<V> forbiddenIfNotSameUser(Context context, TaskView<V> task) {
        if (!task.getUser().equals(context.currentUser())) throw new ForbiddenException();
        return task;
    }
}
