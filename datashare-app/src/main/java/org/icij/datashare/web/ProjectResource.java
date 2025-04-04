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
    import net.codestory.http.constants.HttpStatus;
    import net.codestory.http.payload.Payload;
    import org.apache.commons.io.FileUtils;
    import org.icij.datashare.PropertiesProvider;
    import org.icij.datashare.Repository;
    import org.icij.datashare.asynctasks.TaskManager;
    import org.icij.datashare.cli.DatashareCliOptions;
    import org.icij.datashare.cli.Mode;
    import org.icij.datashare.extract.DocumentCollectionFactory;
    import org.icij.datashare.session.DatashareUser;
    import org.icij.datashare.text.Project;
    import org.icij.datashare.text.indexing.Indexer;
    import org.icij.datashare.utils.DataDirVerifier;
    import org.icij.datashare.utils.IndexAccessVerifier;
    import org.icij.datashare.utils.ModeVerifier;
    import org.icij.datashare.utils.PayloadFormatter;
    import org.icij.extract.queue.DocumentQueue;
    import org.icij.extract.report.ReportMap;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;

    import java.io.File;
    import java.io.IOException;
    import java.nio.file.Path;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;
    import java.util.Properties;
    import java.util.stream.Collectors;
    import java.util.stream.Stream;

    import static java.util.concurrent.TimeUnit.MILLISECONDS;
    import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
    import static net.codestory.http.payload.Payload.ok;
    import static org.apache.tika.utils.StringUtils.isEmpty;
    import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPTION;
    import static org.icij.datashare.text.Project.isAllowed;

    @Singleton
    @Prefix("/api/project")
    public class ProjectResource {
        private final Repository repository;
        private final Indexer indexer;
        private final TaskManager taskManager;
        private final DataDirVerifier dataDirVerifier;
        private final ModeVerifier modeVerifier;
        private final DocumentCollectionFactory<Path> documentCollectionFactory;
        private final PropertiesProvider propertiesProvider;

        @Inject
        public ProjectResource(Repository repository, Indexer indexer, TaskManager taskManager,PropertiesProvider propertiesProvider, DocumentCollectionFactory<Path> documentCollectionFactory) {
            this.repository = repository;
            this.indexer = indexer;
            this.taskManager = taskManager;
            this.propertiesProvider = propertiesProvider;
            this.dataDirVerifier = new DataDirVerifier(propertiesProvider);
            this.modeVerifier = new ModeVerifier(propertiesProvider);
            this.documentCollectionFactory = documentCollectionFactory;
        }

        @Operation(description = "Preflight option request")
        @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS, POST, GET and DELETE")
        @Options("/")
        public Payload rootProjectOpt(String id) {return ok().withAllowMethods("OPTIONS", "POST", "GET", "DELETE");}

        @Operation(description = "Get all user's projects",
                requestBody = @RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = Project[].class)))
        )
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
        @Get("/")
        public List<Project> getProjects(Context context) {
            DatashareUser user = (DatashareUser) context.currentUser();
            return getUserProjects(user);
        }

        @Operation(description = "Creates a project",
                requestBody = @RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = Project.class)))
        )
        @ApiResponse(responseCode = "201", description = "if project and index have been created")
        @ApiResponse(responseCode = "400", description = "if project name is empty")
        @ApiResponse(responseCode = "400", description = "if project path is null or not allowed for the project")
        @ApiResponse(responseCode = "409", description = "if project exists")
        @ApiResponse(responseCode = "500", description = "project creation in DB or index creation failed")
        @Post("/")
        public Payload projectCreate(Project project) {
            modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);

            if (projectExists(project)) {
                return PayloadFormatter.error("Project already exists.", HttpStatus.CONFLICT);
            } else if (isProjectNameEmpty(project)) {
                return PayloadFormatter.error("`name` field is required.", HttpStatus.BAD_REQUEST);
            } else if (isProjectSourcePathNull(project) || !dataDirVerifier.allowed(project.getSourcePath())) {
                return PayloadFormatter.error(String.format("`sourcePath` is required and must not be outside %s.", dataDirVerifier.value()), HttpStatus.BAD_REQUEST);
            } else if (!repository.save(project) || !this.createIndexOnce(project.getId())) {
                return PayloadFormatter.error("Unable to create the project", HttpStatus.INTERNAL_SERVER_ERROR);
            }

            return new Payload(project).withCode(HttpStatus.CREATED);
        }

        @Operation(description = "Preflight project resource option request",
                parameters = {@Parameter(name = "id", description = "project id")}
        )
        @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS, PUT and DELETE")
        @Options("/:id")
        public Payload projectOptions(String id) {return ok().withAllowMethods("OPTIONS", "PUT", "DELETE");}

        @Operation(description = "Gets the project information for the given id",
                parameters = @Parameter(name = "id", in = ParameterIn.QUERY)
        )
        @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
        @ApiResponse(responseCode = "404", description = "if the project is not found in database")
        @Get("/:id")
        public Project projectRead(String id, Context context) {
            return notFoundIfNull(getUserProject((DatashareUser) context.currentUser(), id));
        }

        @Operation(description = "Updates a project",
                requestBody = @RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = Project.class)), required = true)
        )
        @ApiResponse(responseCode = "200", description = "if project has been updated")
        @ApiResponse(responseCode = "404", description = "if project doesn't exist in database")
        @ApiResponse(responseCode = "500", description = "if project json id is not the same as the url id or if save failed")
        @Put("/:id")
        public Payload projectUpdate(String id, Project project) {
            modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
            if (!projectExists(project) || !Objects.equals(project.getId(), id)) {
                return PayloadFormatter.error("Project not found", HttpStatus.NOT_FOUND);
            }
            if (!project.getId().equals(id) || !repository.save(project)) {
                return PayloadFormatter.error("Unable to save the project", HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return new Payload(project).withCode(HttpStatus.OK);
        }

        @Operation(description = "Deletes the project from database and elasticsearch index.",
                parameters = {@Parameter(name = "id", description = "project id")}
        )
        @ApiResponse(responseCode = "204", description = "if project is deleted")
        @ApiResponse(responseCode = "401", description = "if project id is not in the current user's projects")
        @Delete("/:id")
        public Payload projectDelete(String id, Context context) throws IOException {
            modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
            DatashareUser user = (DatashareUser) context.currentUser();
            Project project = getUserProject(user, id);
            Logger logger = LoggerFactory.getLogger(getClass());
            logger.info("Deleted {}'s record: {}", id, repository.deleteAll(id));
            logger.info("Deleted {}'s index: {}", id, indexer.deleteAll(id));
            logger.info("Deleted {}'s queues: {}", id, deleteQueues(project));
            logger.info("Deleted {}'s report map: {}", id, deleteReportMap(project));
            propertiesProvider.get(DatashareCliOptions.ARTIFACT_DIR_OPT).ifPresent(dir -> {
                try {
                    File projectArtifactDir = Path.of(dir).resolve(id).toFile();
                    FileUtils.deleteDirectory(projectArtifactDir);
                    logger.info("Deleted artifacts dir {}", projectArtifactDir);
                } catch (IOException e) {
                    logger.error("cannot delete project {} artifact dir", id, e);
                }
            });
            return new Payload(204);
        }

        @Operation(description = """
                Returns 200 if the project is allowed with this network route : in Datashare database there is the project table that can specify an IP mask that is allowed per project. If the client IP is not in the range, then the file download will be forbidden. In that project table there is a field called `allow_from_mask` that can have a mask with IP and star wildcard.
                
                Ex : `192.168.*.*` will match all subnetwork `192.168.0.0` IP's and only users with an IP in.""",
                parameters = {@Parameter(name = "id", description = "project id")}
        )
        @ApiResponse(responseCode = "200", description = "if project download is allowed for this project and IP")
        @ApiResponse(responseCode = "403", description = "if project download is not allowed")
        @Get("/isDownloadAllowed/:id")
        public Payload isDownloadAllowed(String id, Context context) {
            List<String> projectIds = ((DatashareUser) context.currentUser()).getProjectNames();
            String retrievedProjectId = projectIds.stream()
                    .filter(i -> i.equals(id))
                    .findAny()
                    .orElse(null);

            if (retrievedProjectId == null){
                return ok(); // unknown is allowed
            }
            Project project = repository.getProject(retrievedProjectId);

            if (project != null && !isAllowed(project, context.request().clientAddress()))  {
                return PayloadFormatter.error("Download not allowed", HttpStatus.FORBIDDEN);
            }

            return ok();
        }

        @Operation(description = "Deletes all user's projects from database and elasticsearch index.")
        @ApiResponse(responseCode = "204", description = "if projects are deleted")
        @Delete("/")
        public Payload deleteProjects(Context context) throws IOException {
            DatashareUser user = (DatashareUser) context.currentUser();
            Logger logger = LoggerFactory.getLogger(getClass());
            getUserProjects(user).forEach(project -> {
                try {
                    projectDelete(project.name, context);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            logger.info("Stopping tasks : {}", taskManager.stopTasks(user));
            taskManager.waitTasksToBeDone(TaskManager.POLLING_INTERVAL*2, MILLISECONDS);
            logger.info("Deleted tasks : {}", !taskManager.clearDoneTasks().isEmpty());
            return new Payload(204);
        }

        List<String> getUserProjectIds(DatashareUser user) {
            return user.getProjectNames();
        }

        List<Project> getUserProjects(DatashareUser user) {
            List<String> projectIds = this.getUserProjectIds(user);
            return repository.getProjects(projectIds);
        }

        Project getUserProject(DatashareUser user, String id) {
            return getUserProjects(user)
                    .stream()
                    .filter((Project p) -> p.getId().equals(id))
                    .findAny()
                    .orElse(null);
        }

        boolean deleteQueues(Project project) {
            return getQueues(project).stream().allMatch(DocumentQueue::delete);
        }

        boolean deleteReportMap(Project project) {
            return getReportMap(project).delete();
        }

        List<DocumentQueue<Path>> getQueues(Project project) {
            String name = project.getName();
            Properties properties = propertiesProvider.createOverriddenWith(Map.of("defaultProject", name));
            String defaultQueueName = properties.getOrDefault(QUEUE_NAME_OPTION, "extract:queue").toString();
            String queuePrefix =  defaultQueueName + PropertiesProvider.QUEUE_SEPARATOR + name;
            String queuePattern = queuePrefix + PropertiesProvider.QUEUE_SEPARATOR + "*";
            return Stream.concat(
                    // TODO remove legacy queue name 26/02/2024
                    documentCollectionFactory.getQueues(queuePrefix, Path.class).stream(),
                    documentCollectionFactory.getQueues(queuePattern, Path.class).stream()
            ).collect(Collectors.toList());
        }

        ReportMap getReportMap(String reportMapName) {
            return documentCollectionFactory.createMap(reportMapName);
        }

        ReportMap getReportMap(Project project) {
            String reportMapName = "extract:report:" + project.getName();
            return getReportMap(reportMapName);
        }

        boolean createIndexOnce(String name) {
            try {
                this.indexer.createIndex(IndexAccessVerifier.checkIndices(name));
                return true;
            } catch (IllegalArgumentException | IOException e){
                return false;
            }
        }

        boolean projectExists(Project project) {
            return projectExists(project.getName());
        }

        boolean projectExists(String name) {
            return repository.getProject(name) != null;
        }

        boolean isProjectNameEmpty(Project project) {
            return isEmpty(project.getName());
        }

        boolean isProjectSourcePathNull(Project project) {
            return project.getSourcePath() == null;
        }
    }
