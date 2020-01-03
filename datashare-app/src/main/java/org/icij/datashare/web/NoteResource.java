package org.icij.datashare.web;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.errors.ForbiddenException;
import org.icij.datashare.Note;
import org.icij.datashare.Repository;
import org.icij.datashare.session.HashMapUser;

import java.util.List;

import static org.icij.datashare.text.Project.project;

@Prefix("/api")
public class NoteResource {
    private final Repository repository;

    @Inject
    public NoteResource(Repository repository) {this.repository = repository;}

    /**
     * Gets the list of notes for a project and a document path.
     *
     * if we have on disk:
     *
     * ```
     * /a/b/doc1
     * /a/c/doc2
     * /d/doc3
     * ```
     *
     * And in database
     *
     * projectId | path | note | variant
     * --- | --- | --- | ---
     * p1 | a | note A | info
     * p1 | a/b | note B | danger
     *
     * then :
     * - `GET /api/p1/notes/a/b/doc1` will return note A and B
     * - `GET /api/p1/notes/a/c/doc2` will return note A
     * - `GET /api/p1/notes/d/doc3` will return an empty list
     *
     * If the user doesn't have access to the project she gets a 403 Forbidden
     *
     * @param project the project the note belongs to
     * @param documentPath the document path
     * @param context HTTP context containing the user
     * @return list of Note that match the document path
     *
     * Example:
     * $(curl localhost:8080/api/apigen-datashare/notes/path/to/note/for/doc.txt)
     */
    @Get("/:project/notes/:path:")
    public List<Note> getNotes(String project, String documentPath, Context context) {
        HashMapUser user = (HashMapUser) context.currentUser();
        if (! user.isGranted(project)) {
            throw new ForbiddenException();
        }
        return repository.getNotes(project(project), documentPath);
    }
}
