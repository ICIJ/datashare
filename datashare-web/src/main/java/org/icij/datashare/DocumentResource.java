package org.icij.datashare;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.icij.datashare.session.HashMapUser;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

@Prefix("/api/document")
public class DocumentResource {
    private final Repository repository;
    @Inject
    public DocumentResource(Repository repository) {this.repository = repository;}

    @Get("/starred")
    public List<String> getStarredDocuments(Context context) throws IOException, SQLException {
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
