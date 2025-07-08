package org.icij.datashare.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.Part;
import net.codestory.http.Query;
import net.codestory.http.annotations.Delete;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Post;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.errors.BadRequestException;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.HttpException;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskAlreadyExists;
import org.icij.datashare.asynctasks.TaskFilters;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.WebQueryPagination;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.extract.OptionsWrapper;
import org.icij.datashare.tasks.BatchDownloadRunner;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.DatashareTaskResult;
import org.icij.datashare.tasks.EnqueueFromIndexTask;
import org.icij.datashare.tasks.ExtractNlpTask;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.ScanIndexTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.tasks.UriResult;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.icij.datashare.utils.ModeVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static java.nio.file.Paths.get;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static net.codestory.http.payload.Payload.badRequest;
import static net.codestory.http.payload.Payload.forbidden;
import static net.codestory.http.payload.Payload.ok;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.LambdaExceptionUtils.rethrowFunction;
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
    public static final Set<String> PAGINATION_FIELDS = WebQueryPagination.fields();
    public static final Set<String> TASK_FILTER_FIELDS = Set.of("args", "name", "state", "user");
    private final DatashareTaskFactory taskFactory;
    private final TaskManager taskManager;
    private final PropertiesProvider propertiesProvider;
    private final BatchSearchRepository batchSearchRepository;;
    private final ModeVerifier modeVerifier;
    private final int MAX_BATCH_SIZE = 60000;
    private final ObjectMapper mapper;

    private static final Logger logger = LoggerFactory.getLogger(TaskResource.class);


    @Inject
    public TaskResource(final DatashareTaskFactory taskFactory, final TaskManager taskManager,
                        final PropertiesProvider propertiesProvider, final BatchSearchRepository batchSearchRepository,
                        final ObjectMapper mapper) {
        this.taskFactory = taskFactory;
        this.taskManager = taskManager;
        this.propertiesProvider = propertiesProvider;
        this.batchSearchRepository = batchSearchRepository;
        this.modeVerifier = new ModeVerifier(propertiesProvider);
        this.mapper = mapper;
    }

    @Operation(description = """
            Gets the tasks.
            
            Filters can be added with `name=value`. For example if `name=foo` is given in the request url query,
            the tasks containing the term "foo" are going to be returned. It can contain also dotted keys for nested properties matching.""",
            parameters = {
                @Parameter(name = "from", description = "the offset of the list, starting from 0", in = ParameterIn.QUERY),
                @Parameter(name = "size", description = "the number of element retrieved", in = ParameterIn.QUERY), @Parameter(name = "sort", description = "the name of the parameter to sort on (default: modificationDate)", in = ParameterIn.QUERY),
                @Parameter(name = "name", description = "example: org.icij.datashare.tasks.BatchSearchRunner", in = ParameterIn.QUERY),
                @Parameter(name = "sort", description = "the name of the parameter to use for sort", in = ParameterIn.QUERY),
                @Parameter(name = "order", description = "desc or asc (default)", in = ParameterIn.QUERY)
            })
    @ApiResponse(responseCode = "200", description = "returns the list of tasks", useReturnTypeSchema = true)
    @Get()
    public Payload getTasks(Context context) throws IOException {
        WebQueryPagination pagination = getPagination(context);
        User user = (User) context.currentUser();
        // We need the batch search records of the user to merge them into the tasks
        List<BatchSearchRecord> batchSearchRecords = batchSearchRepository.getRecords(user, user.getProjectNames());
        // We need ALL the tasks to paginate accordingly
        Stream<Task> tasks = taskManager.getTasks(taskFiltersFromContext(context, Pattern.CASE_INSENSITIVE), batchSearchRecords.stream())
            .sorted(new Task.Comparator(pagination.sort, pagination.order));
        WebResponse<Task> paginatedTasks = WebResponse.fromStream(tasks, pagination.from, pagination.size);
        // Then finally, use WebResponse to take display the pagination for us
        return new Payload(paginatedTasks);
    }

    @Operation(description = """
            Gets all the user tasks.
            
            Filters can be added with `name=value`. For example if `name=foo` is given in the request url query,
            the tasks containing the term "foo" are going to be returned. It can contain also dotted keys for nested properties matching.
            
            For example if `args.dataDir=bar` is provided, tasks with an argument "dataDir" containing "bar" are going to be selected.
            
            Pagination/order parameters can be added:
            
            * sort: task field for sorting
            * order: order (desc/asc)
            * from: offset of the slice
            * size: number of tasks in the slice""",
            parameters = {
                @Parameter(name = "name", description = "as an example: pattern contained in the task name", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns the list of tasks", useReturnTypeSchema = true)
    @Get("/all")
    @Deprecated
    public List<Task> getAllTasks(Context context) throws IOException {
        User user = (User) context.currentUser();
        List<BatchSearchRecord> batchSearchRecords = batchSearchRepository.getRecords(user, user.getProjectNames());
        Stream<Task> tasks = taskManager.getTasks(
            taskFiltersFromContext(context, Pattern.CASE_INSENSITIVE), batchSearchRecords.stream()
        );
        return getPagination(context).paginate(tasks, p -> new Task.Comparator(p.sort, p.order)).toList();
    }

    @Operation(description = "Gets one task with its id.")
    @ApiResponse(responseCode = "200", description = "returns the task from its id", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "returns 404 if the task doesn't exist")
    @Get("/:id")
    public Task getTask(@Parameter(name = "id", description = "task id", in = ParameterIn.PATH) String id, Context context) throws IOException {
        User user = (User) context.currentUser();
        try {
            return taskManager.getTask(id);
        } catch (UnknownTask e) {
            return batchSearchRepository
                    .getRecords(user, user.getProjectNames())
                    .stream()
                        .filter(r -> r.uuid.equals(id))
                        .findFirst().map(rethrowFunction(TaskManager::taskify))
                        .orElseThrow(NotFoundException::new);
        }
    }

    @Operation(description = "Create a task with JSON body",
            requestBody = @RequestBody(description = "the task creation body", required = true,  content = @Content(schema = @Schema(implementation = Task.class))),
            parameters = { @Parameter(name = "group", description = "group id", in = ParameterIn.QUERY) })
    @ApiResponse(responseCode = "201", description = "the task has been created", content = @Content(schema = @Schema(implementation = TaskResponse.class)))
    @ApiResponse(responseCode = "200", description = "the task was already existing")
    @ApiResponse(responseCode = "400", description = "bad request, for example the task payload id is not the same as the url id", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "returns 404 if the task doesn't exist")
    @Put("/:id")
    public Payload createTask(@Parameter(name = "id", description = "task id", required = true, in = ParameterIn.PATH) String id,  Context context, Task taskView) throws IOException {
        Group taskGroup = Optional.ofNullable(context.get("group")).map(g -> new Group(TaskGroupType.valueOf(g))).orElse(null);
        if (taskView == null || id == null || !Objects.equals(taskView.id, id)) {
            return new JsonPayload(400, new ErrorResponse("body should contain a taskView, URL id should be present and equal to body id"));
        }
        try {
            return new JsonPayload(201, new TaskResponse(notFoundIfUnknown(() -> taskManager.startTask(taskView, taskGroup))));
        } catch (TaskAlreadyExists e) {
            return new JsonPayload(200);
        }
    }

    @Operation(description = "Gets task result with its id")
    @ApiResponse(responseCode = "200", description = "returns 200 and the result")
    @ApiResponse(responseCode = "403", description = "returns 403 if the task is not belonging to current user")
    @ApiResponse(responseCode = "404", description = "returns 404 if the task doesn't exist or has no result yet")
    @Get("/:id/result")
    public Payload getTaskResult(@Parameter(name = "id", description = "task id", in = ParameterIn.PATH) String id,
                                 Context context) throws IOException {
        Task task = forbiddenIfNotSameUser(context, notFoundIfUnknown(() -> taskManager.getTask(id)));
        if (!task.getState().equals(Task.State.DONE)) {
            throw new NotFoundException();
        }
        byte[] result = taskManager.getTask(id).getResult();
        Object deserializedRes;
        try {
            DatashareTaskResult<?> res = mapper.readValue(result, new TypeReference<>() {
            });
            deserializedRes = res.value();
            if (deserializedRes instanceof UriResult uriResult) {
                Path filePath = Path.of(uriResult.uri().getPath());
                String fileName = filePath.getFileName().toString();
                String contentDisposition = "attachment;filename=\"" + fileName + "\"";
                InputStream fileInputStream = Files.newInputStream(filePath);
                return new Payload(fileInputStream).withHeader("Content-Disposition", contentDisposition);
            }
        } catch (MismatchedInputException e) {
            deserializedRes = result;
        }
        return new Payload("application/json", deserializedRes);
    }

    @Operation(description = """
            Creates a new batch search. This is a multipart form with 9 fields:
            
            name, description, csvFile, published, fileTypes, paths, fuzziness, phrase_matches, query_template.
            
            Queries with less than two characters are filtered.
            
            To make a request manually, you can create a file like:
            ```
            --BOUNDARY
            Content-Disposition: form-data; name="name"
            
            my batch search
            --BOUNDARY
            Content-Disposition: form-data; name="description"
            
            search description
            --BOUNDARY
            Content-Disposition: form-data; name="csvFile"; filename="search.csv"
            Content-Type: text/csv
            
            Obama
            skype
            test
            query three
            --BOUNDARY--
            Content-Disposition: form-data; name="published"
            
            true
            --BOUNDARY--
            ```
            
            Then curl with
            
            ```
            curl -i -XPOST localhost:8080/api/batch/search/prj1,prj2 -H 'Content-Type: multipart/form-data; boundary=BOUNDARY' --data-binary @/home/dev/multipart.txt
            ```
            
            you'll maybe have to replace \\n with \\r\\n with `sed -i 's/$/^M/g' ~/multipart.txt`""",
            requestBody = @RequestBody(description = "multipart form", required = true,
                    content = @Content(mediaType = "multipart/form-data",
                            schemaProperties = {
                                    @SchemaProperty(name = "name", schema = @Schema(implementation = String.class)),
                                    @SchemaProperty(name = "description", schema = @Schema(implementation = String.class)),
                                    @SchemaProperty(name = "uri", schema = @Schema(implementation = String.class)),
                                    @SchemaProperty(name = "csvFile", schema = @Schema(implementation = String.class)),
                                    @SchemaProperty(name = "published", schema = @Schema(implementation = Boolean.class)),
                                    @SchemaProperty(name = "fileTypes", schema = @Schema(implementation = List.class)),
                                    @SchemaProperty(name = "tags", schema = @Schema(implementation = List.class)),
                                    @SchemaProperty(name = "paths", schema = @Schema(implementation = List.class)),
                                    @SchemaProperty(name = "fuzziness", schema = @Schema(implementation = Integer.class)),
                                    @SchemaProperty(name = "phrase_matches", schema = @Schema(implementation = Boolean.class))
                            }
                    )
            ),
            parameters = {@Parameter(description = "Coma-separated list of projects",
                    in = ParameterIn.PATH, examples = @ExampleObject(value = "prj1,prj2"))}
    )
    @ApiResponse(responseCode = "413", description = "if the CSV file is more than 60K lines")
    @ApiResponse(responseCode = "400", description = "if either name or CSV file is missing")
    @Post("/batchSearch/:coma_separated_projects")
    public Payload search(String comaSeparatedProjects, Context context) throws IOException {
        List<Part> parts = context.parts();
        String name = fieldValue("name", parts);
        String csv = fieldValue("csvFile", parts);

        if (name == null || csv == null) {
            return badRequest();
        }

        String description = fieldValue("description", parts);
        String uri = fieldValue("uri", parts);
        boolean published = "true".equalsIgnoreCase(fieldValue("published", parts)) ? TRUE : FALSE;
        List<String> fileTypes = fieldValues("fileTypes", parts);
        String queryTemplate = fieldValue("query_template", parts);
        List<String> paths = fieldValues("paths", parts);
        Optional<Part> fuzzinessPart = parts.stream().filter(p -> "fuzziness".equals(p.name())).findAny();
        int fuzziness = fuzzinessPart.isPresent() ? parseInt(fuzzinessPart.get().content()) : 0;
        Optional<Part> phraseMatchesPart = parts.stream().filter(p -> "phrase_matches".equals(p.name())).findAny();
        boolean phraseMatches = phraseMatchesPart.isPresent() ? parseBoolean(phraseMatchesPart.get().content()) : FALSE;
        LinkedHashSet<String> queries = getQueries(csv)
                .stream().map(query -> (phraseMatches && query.contains("\"")) ? query : sanitizeDoubleQuotesInQuery(query)).collect(Collectors.toCollection(LinkedHashSet::new));
        if(queries.size() >= MAX_BATCH_SIZE) return new Payload(413);

        BatchSearch batchSearch = new BatchSearch(stream(comaSeparatedProjects.split(",")).map(Project::project).collect(Collectors.toList()), name, description, queries,uri,
                (User) context.currentUser(), published, fileTypes, queryTemplate, paths, fuzziness,phraseMatches);
        boolean isSaved = batchSearchRepository.save(batchSearch);
        if (isSaved) {
            taskManager.startTask(batchSearch.uuid, BatchSearchRunner.class, (User) context.currentUser(), Map.of("batchRecord", new BatchSearchRecord(batchSearch)));
        }
        return isSaved ? new Payload("application/json", batchSearch.uuid, 200) : badRequest();
    }

    @Operation(description = "Preflight request", method = "OPTION")
    @ApiResponse(description = "returns POST")
    @Options("/batchSearch/copy/:sourcebatchid")
    public Payload optionsCopy(String sourceBatchId, Context context) {
        return ok().withAllowMethods("OPTIONS", "POST");
    }

    @Operation( description = "Creates a new batch search based on a previous one given its id, and enqueue it for running",
            parameters = {@Parameter(name = "sourcebatchid", in = ParameterIn.PATH, description = "source batch id")},
            requestBody = @RequestBody(description = "batch parameters", required = true,
                    content = @Content( mediaType = "application/json",
                            examples = {@ExampleObject(value = "{\"name\": \"my new batch\", \"description\":\"desc\"}")})
            )
    )
    @ApiResponse(responseCode = "404", description = "if the source batch search is not found in database")
    @ApiResponse(responseCode = "200", description = "returns the id of the created batch search", useReturnTypeSchema = true)
    @Post("/batchSearch/copy/:sourcebatchid")
    public String copySearch(String sourceBatchId, Context context) throws IOException {
        BatchSearch sourceBatchSearch = batchSearchRepository.get((User) context.currentUser(), sourceBatchId);
        if (sourceBatchSearch == null) {
            throw new NotFoundException();
        }
        BatchSearch copy = new BatchSearch(sourceBatchSearch, context.extract(HashMap.class));
        boolean isSaved = batchSearchRepository.save(copy);
        if (isSaved) taskManager.startTask(copy.uuid, BatchSearchRunner.class, (User) context.currentUser(), Map.of("batchRecord", new BatchSearchRecord(copy)));
        return copy.uuid;
    }


    @Operation(description = "Preflight request for batch download.")
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS and POST")
    @Options("/batchDownload")
    public Payload batchDownloadPreflight(final Context context) {
        return ok().withAllowMethods("OPTIONS", "POST").withAllowHeaders("Content-Type");
    }

    @Operation(description = """
            Download files from a search query.
            
             Expected parameters are:
            
            - project: string
            - query: string or elasticsearch JSON query
            
            If the query is a string it is taken as an ES query string, else it is a raw JSON query (without the query part),
            see org.elasticsearch.index.query.WrapperQueryBuilder that is used to wrap the query.
            """,
            requestBody = @RequestBody(description = "the json used to wrap the query", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the json task id", useReturnTypeSchema = true)
    @Post("/batchDownload")
    public TaskResponse batchDownload(final OptionsWrapper<Object> optionsWrapper, Context context) throws IOException {
        Map<String, Object> options = optionsWrapper.getOptions();
        Properties properties = applyProjectProperties(optionsWrapper);
        Path downloadDir = get(properties.getProperty(BATCH_DOWNLOAD_DIR_OPT));
        if (!downloadDir.toFile().exists()) downloadDir.toFile().mkdirs();
        String query = options.get("query") instanceof Map ? mapper.writeValueAsString(options.get("query")): (String)options.get("query");
        String uri = (String) options.get("uri");
        boolean batchDownloadEncrypt = parseBoolean(properties.getOrDefault("batchDownloadEncrypt", "false").toString());
        List<String> projectIds = (List<String>) options.get("projectIds");

        BatchDownload batchDownload = new BatchDownload(projectIds.stream().map(Project::project).collect(toList()), (User) context.currentUser(), query, uri, downloadDir, batchDownloadEncrypt);

        return new TaskResponse(taskManager.startTask(BatchDownloadRunner.class, (User) context.currentUser(), Map.of("batchDownload", batchDownload)));
    }

    @Operation(description = "Indexes files from the queue.",
        requestBody = @RequestBody(description = "wrapper for options json", required = true, content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the json task id", content = @Content(schema = @Schema(implementation = TaskResponse.class)))
    @ApiResponse(responseCode = "500", description = "returns an error when stat task fails", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @Post("/batchUpdate/index")
    public Payload indexQueue(final OptionsWrapper<String> optionsWrapper, Context context)
        throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
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
    public Payload indexFile(@Parameter(name = "filePath", description = "path of the directory", in = ParameterIn.PATH) final String filePath, final OptionsWrapper<String> optionsWrapper, Context context)
        throws Exception {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        TaskResponse scanResponse = scanFile(filePath, optionsWrapper, context);
        List<String> taskIds = new LinkedList<>();
        taskIds.add(scanResponse.taskId);
        Properties properties = applyProjectProperties(optionsWrapper);
        User user = (User) context.currentUser();
        Task scanIndex;
        // Use a report map only if the request's body contains a "filter" attribute
        if (properties.get("filter") != null && Boolean.parseBoolean(properties.getProperty("filter"))) {
            // TODO remove taskFactory.createScanIndexTask would allow to get rid of taskfactory dependency in taskresource
            // problem for now is that if we call taskManager.startTask(ScanIndexTask.class.getName(), user, propertiesToMap(properties))
            // the task will be run as a background task that will have race conditions with indexTask report loading
            scanIndex = new Task(ScanIndexTask.class.getName(), user, propertiesToMap(properties));
            taskFactory.createScanIndexTask(scanIndex, (p) -> null).runTask();
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
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        Path path = IS_OS_WINDOWS ?  get(filePath) : get(File.separator, filePath);
        Properties properties = applyProjectProperties(optionsWrapper);
        properties.setProperty(DATA_DIR_OPT, path.toString());
        return ofNullable(taskManager.startTask(ScanTask.class, (User) context.currentUser(), propertiesToMap(properties)))
                .map(TaskResponse::new).orElseThrow(() -> new HttpException(500));
    }

    @Operation(description = "Cleans all DONE tasks.", parameters = {
            @Parameter(name = "name", description = "as an example: pattern contained in the task name", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns 200 and the list of removed tasks", useReturnTypeSchema = true)
    @Post("/clean")
    public List<Task> cleanDoneTasks(final Context context) throws IOException {
        return taskManager.clearDoneTasks(taskFiltersFromContext(context));
    }

    @Operation(description = "Cleans a specific task.")
    @ApiResponse(responseCode = "200", description = "returns 200 if the task is removed")
    @ApiResponse(responseCode = "403", description = "returns 403 if the task is still in RUNNING state")
    @ApiResponse(responseCode = "403", description = "returns 403 if the task is still in RUNNING state")
    @ApiResponse(responseCode = "404", description = "returns 404 if the task doesn't exist")
    @Delete("/clean/:taskName:")
    public Payload cleanTask(@Parameter(name = "taskName", description = "name of the task to delete", in = ParameterIn.PATH) final String taskId, Context context) throws Exception {
        Task task = forbiddenIfNotSameUser(context, notFoundIfUnknown(() -> taskManager.getTask(taskId)));
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
    @ApiResponse(responseCode = "404", description = "returns 404 if the task doesn't exist")
    @Put("/stop/:taskId:")
    public boolean stopTask(@Parameter(name = "taskName", description = "name of the task to cancel", in = ParameterIn.PATH) final String taskId) throws IOException {
        return notFoundIfUnknown(() -> taskManager.stopTask(notFoundIfUnknown(() -> taskManager.getTask(taskId)).id));
    }

    @Operation(description = "Preflight request to stop tasks.")
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS and PUT")
    @Options("/stop/:taskName:")
    public Payload stopTaskPreflight(final String taskName) {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    @Operation(description = """
            [DEPRECATED] Will be removed in version 20.0.0. Use `/api/task/stop` instead.
            Cancels the running tasks. It returns a map with task name/stop statuses.
            
            If the status is false, it means that some threads have not been stopped.""",
            parameters = {
                    @Parameter(name = "name", description = "as an example: pattern contained in the task name", in = ParameterIn.QUERY)},
            deprecated = true)
    @ApiResponse(responseCode = "200", description = "returns 200 and the tasks stop result map", useReturnTypeSchema = true)
    @Put("/stopAll")
    public Map<String, Boolean> stopAllTasks(final Context context) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        return taskManager.stopTasks(taskFiltersFromContext(context));
    }

    @Operation(description = """
            [DEPRECATED] Will be removed in version 20.0.0. Use `/api/task/stop`instead. 
            Preflight request to stop all tasks.""", deprecated = true)
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS and PUT")
    @Options("/stopAll")
    public Payload stopAllTasksPreflight() {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    @Operation(description = """
            Cancels the running tasks. It returns a map with task name/stop statuses.
            
            If the status is false, it means that some threads have not been stopped.""",
            parameters = {
                    @Parameter(name = "name", description = "as an example: pattern contained in the task name", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns 200 and the tasks stop result map", useReturnTypeSchema = true)
    @Put("/stop")
    public Map<String, Boolean> stopTasks(final Context context) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        TaskFilters filters = taskFiltersFromContext(context);
        return taskManager.stopTasks(filters);
    }

    @Operation(description = """
            [DEPRECATED] Will be removed in version 20.0.0. Use `/api/task/stop`instead.
            Preflight request to stop all tasks.""", deprecated = true)
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS and PUT")
    @Options("/stop")
    public Payload stopTasksPreflight() {
        return ok().withAllowMethods("OPTIONS", "PUT");
    }

    @Operation(description = """
            Find names using the given pipeline:
            
            - OPENNLP
            - CORENLP
            - SPACY
            
            This endpoint is going to find all Documents that are not tagged with the given pipeline and extract named entities for all these documents.
            """,
            requestBody = @RequestBody(description = "wrapper for options json", required = true,  content = @Content(schema = @Schema(implementation = OptionsWrapper.class))))
    @ApiResponse(responseCode = "200", description = "returns 200 and the created task ids", content = @Content(schema = @Schema(implementation = TasksResponse.class)))
    @Post("/findNames/:pipeline")
    public Payload extractNlp(@Parameter(name = "pipeline", description = "name of the NLP pipeline to use", in = ParameterIn.PATH) final String pipelineName, final OptionsWrapper<String> optionsWrapper, Context context) throws IOException {
        modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
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

    private static Task forbiddenIfNotSameUser(Context context, Task task) {
        if (!task.getUser().equals(context.currentUser())) throw new ForbiddenException();
        return task;
    }

    // JSON responses
    public record ErrorResponse(String message) {}
    public record TaskResponse(String taskId) {}
    public record TasksResponse(List<String> taskIds) {}

    private String fieldValue(String field, List<Part> parts) {
        List<String> values = fieldValues(field, parts);
        return values.isEmpty() ? null: values.get(0);
    }

    private List<String> fieldValues(String field, List<Part> parts) {
        return parts.stream().filter(p -> field.equals(p.name())).map(part -> {
            try {
                return part.content();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }

    private String sanitizeDoubleQuotesInQuery(String query) {
        if(query.contains("\"\"\"")) {
            return query.substring(1, query.length() - 1).replaceAll("\"\"","\"");
        }
        return query;
    }

    private LinkedHashSet<String> getQueries(String csv) {
        return asSet(stream(csv.split("\r?\n")).filter(q -> q.length() >= 2).toArray(String[]::new));
    }

    private interface UnknownTaskThrowingSupplier<T>  {
        T get() throws IOException, UnknownTask;
    }

    private static <T> T notFoundIfUnknown(UnknownTaskThrowingSupplier<T> supplier) throws IOException {
        try {
            return supplier.get();
        } catch (UnknownTask ex) {
            throw new NotFoundException();
        }
    }

    private static WebQueryPagination getPagination(Context context) {
        Map<String, Object> paginationMap = context
                .query()
                .keys()
                .stream()
                .filter(PAGINATION_FIELDS::contains)
                .collect(toMap(Function.identity(), context::get));
        return WebQueryPagination.fromMap(paginationMap);
    }

    private static TaskFilters taskFiltersFromContext(Context context) throws BadRequestException {
        return taskFiltersFromContext(context, null);
    }

    // TODO: this is for backwards compatibility, we should updated APIs to use TaskFilters instead
    protected static TaskFilters taskFiltersFromContext(Context context, Integer regexFlags) throws BadRequestException {
        TaskFilters filters = TaskFilters.empty();
        Query query = context.query();
        validatedFilterKeys(query);
        // User
        filters = Optional.ofNullable((User) context.currentUser()).map(filters::withUser).orElse(filters);
        // States
        // We had regexes for state matching, probably not used as such. In case they were used we expect the user to
        // provide a single state or multiple state joined with "|". This is a hacks.
        Set<Task.State> states = query.keys().stream().filter(k -> k.equals("state")).findAny()
            .map(k -> stream(query.get(k).split("\\|")).map(Task.State::valueOf).collect(Collectors.toSet()))
            .orElse(Set.of());
        filters = filters.withStates(states);
        // Names
        Optional<String> maybeName = query.keys().stream().filter(k -> k.equals("name")).findAny()
            .map(k -> ".*" + query.get(k) + ".*");
        if (maybeName.isPresent()) {
            filters = filters.withNames(maybeName.get());
        }
        // Args
        List<TaskFilters.ArgsFilter> args = query.keys().stream().filter(k -> k.startsWith("args."))
            .map(k -> new TaskFilters.ArgsFilter(k.substring(5), ".*" + query.get(k) + ".*"))
            .toList();
        filters = filters.withArgs(args);
        if (regexFlags != null) {
            filters = filters.withFlag(regexFlags);
        }
        return filters;
    }

    private static void validatedFilterKeys(Query query) throws BadRequestException {
        Set<String> extraKeys = query.keys().stream()
            .filter(not(PAGINATION_FIELDS::contains))
            .filter(not(TASK_FILTER_FIELDS::contains))
            // We allows nested args search
            .filter(not(k -> k.startsWith("args.")))
            .collect(Collectors.toSet());
        if (!extraKeys.isEmpty()) {
            String msg = "invalid task filter keys " + extraKeys.stream().sorted().toList() + ".";
            msg += " Allowed keys" + TASK_FILTER_FIELDS.stream().sorted().toList();
            logger.error(msg);
            throw new BadRequestException();
        }
    }
}
