package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Delete;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Options;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Repository;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;

import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.text.Project.isAllowed;
import static org.icij.datashare.text.Project.project;

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
     * $(curl -H 'Content-Type:application/json' localhost:8080/api/project/id/apigen-datashare
     *)
     */
    @Get("/id/:id")
    public Project getProject(String id) {
        Project project = repository.getProject(id);
        return project == null ? project(id):project;
    }

    /**
     * Returns if the project is allowed with this network route : in datashare database
     * there is the project table that can specify an IP mask that is allowed per project.
     * If the client IP is not in the range, then the file download will be forbidden.
     *
     * @param id
     * @return 200 or 403 (Forbidden)
     *
     * Example :
     * $(curl -H 'Content-Type:application/json' localhost:8080/api/project/isAllowed/apigen-datashare)
     * Example :
     * $(curl -H 'Content-Type:application/json' localhost:8080/api/project/isAllowed/disallowed-project)
     */
    @Get("/isAllowed/:id")
    public Payload isProjectAllowed(String id, Context context) {
        return isAllowed(repository.getProject(id), context.request().clientAddress()) ? ok(): Payload.forbidden();
    }

    /**
     * Preflight option request
     * @param id
     * @return 200 DELETE
     */
    @Options("/id/:id")
    public Payload deleteProjectOpt(String id) {return ok().withAllowMethods("OPTIONS", "DELETE");}

    /**
     * Delete the project from database and elasticsearch indices.
     *
     * It returns 204 (no content) when something has been removed (index and/or database), or
     * 404 if nothing has been removed (i.e. index and database don't exist).
     *
     * If the project id is not the current user project (local-datashare in local mode),
     * then it will return 401 (unauthorized)
     *
     * @param id
     * @return 204 (no content) or 404
     *
     * Example :
     * $(curl -I -XDELETE -H 'Content-Type:application/json' localhost:8080/api/project/id/unknown-project)
     */
    @Delete("/id/:id")
    public Payload deleteProject(String id, Context context) throws Exception {
        if (!((User) context.currentUser()).projectName().equals(id)) {
            return new Payload(401);
        }
        boolean isDeleted = this.repository.deleteAll(id);
        boolean indexDeleted = this.indexer.deleteAll(id);
        return isDeleted || indexDeleted ? new Payload(204): new Payload(404);
    }
}
