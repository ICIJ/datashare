package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.errors.ForbiddenException;
import org.icij.datashare.Note;
import org.icij.datashare.Repository;
import org.icij.datashare.session.DatashareUser;

import java.util.List;

import static org.icij.datashare.text.Project.project;

@Singleton
@Prefix("/api")
public class NoteResource {
    private final Repository repository;

    @Inject
    public NoteResource(Repository repository) {this.repository = repository;}

    @Operation(description = "Gets the list of notes for a project and a document path.<br/>" +
            "if we have on disk:" +
            "<pre>" +
            "/a/b/doc1<br/>" +
            "/a/c/doc2<br/>" +
            "/d/doc3<br/>" +
            "</pre>" +
            "And in database:" +
            "<pre>" +
            "projectId | path | note | variant<br/>" +
            "--- | --- | --- | ---<br/>" +
            "p1 | a | note A | info<br/>" +
            "p1 | a/b | note B | danger" +
            "</pre>" +
            " then :" +
            "<pre>" +
            "- `GET /api/p1/notes/a/b/doc1` will return note A and B<br/>" +
            "- `GET /api/p1/notes/a/c/doc2` will return note A<br/>" +
            "- `GET /api/p1/notes/d/doc3` will return an empty list<br/>" +
            "</pre>",
            parameters = {
                @Parameter(name = "project", description = "the project id"),
                @Parameter(name = "path", description = "the path of the document. It is a greedy parameter at the end of the url"),
            }
    )
    @ApiResponse(responseCode = "403", description = "if the user is not granted for the project")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Get("/:project/notes/:path:")
    public List<Note> getPathNotes(String project, String documentPath, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        if (! user.isGranted(project)) {
            throw new ForbiddenException();
        }
        return repository.getNotes(project(project), documentPath);
    }

    @Operation(description = "Gets the list of notes for a project.",
            parameters = {@Parameter(name = "project", description = "the project id"),}
    )
    @ApiResponse(responseCode = "403", description = "if the user is not granted for the project")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Get("/:project/notes")
    public List<Note> getProjectNotes(String project, Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        if (! user.isGranted(project)) {
            throw new ForbiddenException();
        }
        return repository.getNotes(project(project));
    }
}
