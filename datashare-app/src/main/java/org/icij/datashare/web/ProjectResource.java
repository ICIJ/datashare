package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static net.codestory.http.payload.Payload.ok;
import static org.apache.tika.utils.StringUtils.isEmpty;
import static org.icij.datashare.text.Project.isAllowed;
import static org.icij.datashare.text.Project.project;

@Singleton
@Prefix("/api/project")
public class ProjectResource {
    private final Repository repository;
    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;

    @Inject
    public ProjectResource(Repository repository, Indexer indexer, PropertiesProvider propertiesProvider) {
        this.repository = repository;
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
    }

    private void checkAllowedMode(Mode ...modes) throws ForbiddenException {
        String modeName = this.propertiesProvider.get("mode").orElse(null);
        if (modeName != null) {
            Mode mode = Mode.valueOf(modeName);
            if (!Arrays.asList(modes).contains(mode)) {
                throw new ForbiddenException();
            }
        }
    }

    @Get("/")
    public List<Project> getProjects(Context context) {
        String[] projectIds = ((DatashareUser) context.currentUser()).getProjects().toArray(new String[] {});
        return repository.getProjects(projectIds);
    }

    @Post("/")
    public Payload createProject(Context context) throws IOException {
        this.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        Project project =  context.extract(Project.class);
        if (isEmpty(project.name)) {
            return new Payload("`name` field is required.").withCode(HttpStatus.BAD_REQUEST);
        }
        if (project.sourcePath == null) {
            return new Payload("`sourcePath` field is required.").withCode(HttpStatus.BAD_REQUEST);
        }
        if (!this.isDataDirAllowed(project.sourcePath)) {
            return new Payload(String.format("`sourcePath` cannot be outside %s.", this.dataDir())).withCode(HttpStatus.BAD_REQUEST);
        }
        if (!repository.save(project)) {
            return new Payload("Unable to save the project").withCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new Payload(project).withCode(HttpStatus.CREATED);
    }

    @Put("/:id")
    public Payload updateProject(String id, @NotNull Context context) throws IOException {
        this.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        Project project =  context.extract(Project.class);
        if (repository.getProject(id) == null) {
            return new Payload("Project not found").withCode(HttpStatus.NOT_FOUND);
        }
        if (!project.getId().equals(id) || !repository.save(project)) {
            return new Payload("Unable to save the project").withCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new Payload(project).withCode(HttpStatus.OK);
    }

    /**
     * Gets the project information for the given project id.
     *
     * @param id
     * @return 200 and the project from database if it exists else a transient one
     *
     * Example :
     *
     * $(curl -H 'Content-Type:application/json' localhost:8080/api/project/apigen-datashare
     *)
     */
    @Get("/:id")
    public Project getProject(String id) {
        Project project = repository.getProject(id);
        return project == null ? project(id) : project;
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
        this.checkAllowedMode(Mode.LOCAL, Mode.EMBEDDED);
        boolean isDeleted = this.repository.deleteAll(id);
        boolean indexDeleted = this.indexer.deleteAll(id);
        LoggerFactory.getLogger(getClass()).info("deleted project {} index (deleted={}) and db (deleted={})", id, indexDeleted, isDeleted);
        return new Payload(204);
    }

    protected String dataDir () {
        return propertiesProvider.get("dataDir").orElse("/home/datashare/data");
    }

    protected Path dataDirPath () {
        return Paths.get(this.dataDir());
    }

    protected boolean isDataDirAllowed (Path path) {
        return path.equals(this.dataDirPath()) || path.startsWith(this.dataDirPath());
    }
}
