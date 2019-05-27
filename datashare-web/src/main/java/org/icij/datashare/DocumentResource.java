package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.HashMapUser;
import org.icij.datashare.text.Document;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import static org.icij.datashare.text.Project.project;

@Prefix("/api/document")
public class DocumentResource {
    private final Repository repository;
    @Inject
    public DocumentResource(Repository repository) {this.repository = repository;}

    @Put("/project/star/:project/:docId")
    public Payload starProjectDocument(final String projectId, final String docId, Context context) throws IOException, SQLException {
        return repository.star(project(projectId), (HashMapUser)context.currentUser(), docId) ? Payload.created(): Payload.ok();
    }

    @Put("/project/unstar/:project/:docId")
    public Payload unstarProjectDocument(final String projectId, final String docId, Context context) throws IOException, SQLException {
        return repository.unstar(project(projectId), (HashMapUser)context.currentUser(), docId) ? Payload.created(): Payload.ok();
    }

    @Get("/project/starred/:project")
    public List<String> getProjectStarredDocuments(final String projectId, Context context) throws IOException, SQLException {
        return repository.getStarredDocuments(project(projectId), (HashMapUser)context.currentUser());
    }

    @Get("/starred")
    public List<Document> getStarredDocuments(Context context) throws IOException, SQLException {
        return repository.getStarredDocuments((HashMapUser)context.currentUser());
    }

    @Put("/star/:docId")
    public Payload starDocument(final String docId, Context context) throws IOException, SQLException {
        return repository.star((HashMapUser)context.currentUser(), docId) ? Payload.created(): Payload.ok();
    }

    @Put("/unstar/:docId")
    public Payload unstarDocument(final String docId, Context context) throws IOException, SQLException {
        return repository.unstar((HashMapUser)context.currentUser(), docId) ? Payload.created(): Payload.ok();
    }
}
