package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.annotations.Delete;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Repository;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.LoggerFactory;

import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.text.Project.isAllowed;
import static org.icij.datashare.text.Project.project;

@Singleton
@Prefix("/api/project")
public class ProjectResource {
    private final Repository repository;
    private final Indexer indexer;

    @Inject
    public ProjectResource(Repository repository, Indexer indexer) {
        this.repository = repository;
        this.indexer = indexer;
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
        return project == null ? project(id):project;
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
        if (!context.currentUser().isInRole("local")) {
            return new Payload(401);
        }
        boolean isDeleted = this.repository.deleteAll(id);
        boolean indexDeleted = this.indexer.deleteAll(id);
        LoggerFactory.getLogger(getClass()).info("deleted project {} index (deleted={}) and db (deleted={})", id, indexDeleted, isDeleted);

        return new Payload(204);
    }
}
