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

    @Get("/id/:id")
    public Project getProject(String id) {
        Project project = repository.getProject(id);
        return project == null ? project(id):project;
    }

    @Get("/isAllowed/:id")
    public Payload isProjectAllowed(String id, Context context) {
        return isAllowed(repository.getProject(id), context.request().clientAddress()) ? ok(): Payload.forbidden();
    }

    @Options("/id/:id")
    public Payload deleteProjectOpt(String id) {return ok().withAllowMethods("OPTIONS", "DELETE");}

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
