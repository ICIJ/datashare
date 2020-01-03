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
     * Gets the list of notes for a project and an url
     *
     * If the user doesn't have access to the project she gets a 403 Forbidden
     *
     * @param project the project the note belongs to
     * @param pathPrefix the path prefix matching the notes
     * @param context HTTP context containing the user
     * @return list of Note
     *
     * Example:
     * $(curl -H 'Content-Type:application/json' localhost:8080/api/project/notes/url)
     */
    @Get("/:project/notes/:url:")
    public List<Note> getNotes(String project, String pathPrefix, Context context) {
        HashMapUser user = (HashMapUser) context.currentUser();
        if (! user.isGranted(project)) {
            throw new ForbiddenException();
        }
        return repository.getNotes(project(project), pathPrefix);
    }
}
