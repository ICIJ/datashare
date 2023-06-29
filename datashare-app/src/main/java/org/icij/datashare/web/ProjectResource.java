    package org.icij.datashare.web;

    import com.google.inject.Inject;
    import com.google.inject.Singleton;
    import net.codestory.http.Context;
    import net.codestory.http.annotations.*;
    import net.codestory.http.constants.HttpStatus;
    import net.codestory.http.payload.Payload;
    import net.codestory.http.security.User;
    import org.icij.datashare.PropertiesProvider;
    import org.icij.datashare.Repository;
    import org.icij.datashare.cli.Mode;
    import org.icij.datashare.session.DatashareUser;
    import org.icij.datashare.text.Project;
    import org.icij.datashare.text.indexing.Indexer;
    import org.icij.datashare.utils.DataDirVerifier;
    import org.icij.datashare.utils.ModeVerifier;
    import org.icij.datashare.utils.PayloadFormatter;
    import org.jetbrains.annotations.NotNull;
    import org.slf4j.LoggerFactory;

    import java.io.IOException;
    import java.util.*;
    import java.util.stream.Stream;

    import static net.codestory.http.payload.Payload.ok;
    import static org.apache.tika.utils.StringUtils.isEmpty;
    import static org.icij.datashare.text.Project.isAllowed;

    @Singleton
    @Prefix("/api/project")
    public class ProjectResource {
        private final Repository repository;
        private final Indexer indexer;
        private final PropertiesProvider propertiesProvider;
        private final DataDirVerifier dataDirVerifier;
        private final ModeVerifier modeVerifier;
        private final PayloadFormatter payloadFormatter;


        @Inject
        public ProjectResource(Repository repository, Indexer indexer, PropertiesProvider propertiesProvider) {
            this.repository = repository;
            this.indexer = indexer;
            this.propertiesProvider = propertiesProvider;
            this.dataDirVerifier = new DataDirVerifier(propertiesProvider);;
            this.modeVerifier = new ModeVerifier(propertiesProvider);
            this.payloadFormatter = new PayloadFormatter();
        }

        String[] getServerModeUserProjectIds(DatashareUser user) {
            return user.getProjects().toArray(String[]::new);
        }

        String[] getAllProjectIds () {
            return repository.getProjects().stream().map(Project::getId).toArray(String[]::new);
        }

        String[] getUserProjectIds(DatashareUser user) {
            String modeName = this.propertiesProvider.get("mode").orElse(null);
            if (!Mode.SERVER.name().equals(modeName)) {
                return Stream.of(this.getServerModeUserProjectIds(user), this.getAllProjectIds())
                        .flatMap(Stream::of)
                        .toArray(String[]::new);
            }
            return this.getServerModeUserProjectIds(user);
        }

        List<Project> getUserProjects(User user) {
            String[] projectIds = this.getUserProjectIds((DatashareUser)  user);
            return repository.getProjects(projectIds);
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

        @Get("/")
        public List<Project> getProjects(Context context) {
            User user = context.currentUser();
            return getUserProjects(user);
        }

        @Post("/")
        public Payload createProject(Context context) throws IOException {
            modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
            Project project = context.extract(Project.class);

            if (projectExists(project)) {
                return payloadFormatter.error("Project already exists.", HttpStatus.CONFLICT);
            } else if (isProjectNameEmpty(project)) {
                return payloadFormatter.error("`name` field is required.", HttpStatus.BAD_REQUEST);
            } else if (isProjectSourcePathNull(project) || !dataDirVerifier.allowed(project.getSourcePath())) {
                return payloadFormatter.error(String.format("`sourcePath` is required and must not be outside %s.", dataDirVerifier.value()), HttpStatus.BAD_REQUEST);
            } else if (!repository.save(project) || !this.indexer.createIndex(project.getId())) {
                return payloadFormatter.error("Unable to create the project", HttpStatus.INTERNAL_SERVER_ERROR);
            }

            return new Payload(project).withCode(HttpStatus.CREATED);
        }

        @Put("/:id")
        public Payload updateProject(String id, @NotNull Context context) throws IOException {
            modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
            Project project = context.extract(Project.class);
            if (!projectExists(project) || !Objects.equals(project.getId(), id)) {
                return payloadFormatter.error("Project not found", HttpStatus.NOT_FOUND);
            }
            if (!project.getId().equals(id) || !repository.save(project)) {
                return payloadFormatter.error("Unable to save the project", HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return new Payload(project).withCode(HttpStatus.OK);
        }

        /**
         * Gets the project information for the given project id.
         *
         * @param id
         * @return 200 and the project from database if it exists
         * <p>
         * Example :
         * <p>
         * $(curl -H 'Content-Type:application/json' localhost:8080/api/project/apigen-datashare
         * )
         */
        @Get("/:id")
        public Payload getProject(String id) {
            Project project = repository.getProject(id);
            if (project == null) {
                return payloadFormatter.error("Project not found", HttpStatus.NOT_FOUND);
            }
            return new Payload(project).withCode(HttpStatus.OK);
        }

        /**
         * Returns if the project is allowed with this network route : in datashare database
         * there is the project table that can specify an IP mask that is allowed per project.
         * If the client IP is not in the range, then the file download will be forbidden.
         *
         * in that project table there is a field called `allow_from_mask` that can have a mask
         * with IP and star wildcard.
         *
         * Ex : `192.168.*.*` will match all subnetwork 192.168.0.0 IP's and only users with an IP in
         * this range will be granted for downloading documents.
         *
         * @param id
         * @return 200 or 403 (Forbidden)
         *
         * Example :
         * $(curl -i -H 'Content-Type:application/json' localhost:8080/api/project/isDownloadAllowed/apigen-datashare)
         * Example :
         * $(curl -i -H 'Content-Type:application/json' localhost:8080/api/project/isDownloadAllowed/disallowed-project)
         */
        @Get("/isDownloadAllowed/:id")
        public Payload isProjectAllowed(String id, Context context) {
            return isAllowed(repository.getProject(id), context.request().clientAddress()) ? ok(): Payload.forbidden();
        }

        /**
         * Preflight option request
         * @param id
         * @return 200 DELETE
         */
        @Options("/:id")
        public Payload deleteProjectOpt(String id) {return ok().withAllowMethods("OPTIONS", "DELETE");}

        /**
         * Delete the project from database and elasticsearch indices.
         *
         * It always returns 204 (no content) or 500 if an error occurs.
         *
         * If the project id is not the current user project (local-datashare in local mode),
         * then it will return 401 (unauthorized)
         *
         * @param id
         * @return 204
         *
         * Example :
         * $(curl -I -XDELETE -H 'Content-Type:application/json' localhost:8080/api/project/unknown-project)
         */
        @Delete("/:id")
        public Payload deleteProject(String id, Context context) throws Exception {
            modeVerifier.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
            boolean isDeleted = repository.deleteAll(id);
            boolean indexDeleted = indexer.deleteAll(id);
            LoggerFactory.getLogger(getClass()).info("deleted project {} index (deleted={}) and db (deleted={})", id, indexDeleted, isDeleted);
            return new Payload(204);
        }
    }
