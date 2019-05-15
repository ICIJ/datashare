package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;

import java.sql.SQLException;
import java.util.List;

public interface Repository {
    NamedEntity getNamedEntity(String id) throws SQLException;
    Document getDocument(String id) throws SQLException;
    void create(List<NamedEntity> neList) throws SQLException;
    void create(Document document) throws SQLException;

    List<Document> getDocumentsNotTaggedWithPipeline(Project project, Pipeline.Type type) throws SQLException;
    // user related
    boolean star(User user, String documentId) throws SQLException;
    boolean unstar(User user, String documentId) throws SQLException;
    List<String> getStarredDocuments(User user) throws SQLException;
}
