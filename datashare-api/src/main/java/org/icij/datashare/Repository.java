package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;

import java.util.List;

public interface Repository {
    NamedEntity getNamedEntity(String id);
    Document getDocument(String id);
    void create(List<NamedEntity> neList);
    void create(Document document);

    // user related
    boolean star(User user, String documentId);
    boolean unstar(User user, String documentId);
    List<Document> getStarredDocuments(User user);

    // project related
    List<Document> getDocumentsNotTaggedWithPipeline(Project project, Pipeline.Type type);
    // standalone (to remove later ?)
    int star(Project project, User user, List<String> documentIds);
    int unstar(Project project, User user, List<String> documentIds);
    List<String> getStarredDocuments(Project project, User user);

    boolean tag(Project prj, String documentId, Tag... tags);
    boolean untag(Project prj, String documentId, Tag... tags);
    boolean tag(Project prj, List<String> documentIds, Tag... tags);
    boolean untag(Project prj, List<String> documentIds, Tag... tags);
    List<String> getDocuments(Project project, Tag... tags);
    List<Tag> getTags(Project project, String documentId);

    boolean deleteAll(String projectId);
    Project getProject(String projectId);

    List<Note> getNotes(Project prj, String pathPrefix);
    boolean save(Note note);
}
