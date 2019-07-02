package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;

import java.sql.SQLException;
import java.util.List;

public interface Repository {
    NamedEntity getNamedEntity(String id) throws SQLException;
    Document getDocument(String id) throws SQLException;
    void create(List<NamedEntity> neList) throws SQLException;
    void create(Document document) throws SQLException;

    // user related
    boolean star(User user, String documentId) throws SQLException;
    boolean unstar(User user, String documentId) throws SQLException;
    List<Document> getStarredDocuments(User user) throws SQLException;

    // project related
    List<Document> getDocumentsNotTaggedWithPipeline(Project project, Pipeline.Type type) throws SQLException;
    // standalone (to remove later ?)
    boolean star(Project project, User user, String documentId) throws SQLException;
    boolean unstar(Project project, User user, String documentId) throws SQLException;
    List<String> getStarredDocuments(Project project, User user) throws SQLException;

    boolean tag(Project prj, String documentId, Tag... tags) throws SQLException;
    boolean untag(Project prj, String documentId, Tag... tags) throws SQLException;
    List<String> getDocuments(Project project, Tag... tags) throws SQLException;
}
