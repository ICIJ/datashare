package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;

import java.util.List;
import java.util.Set;

public interface Repository {
    NamedEntity getNamedEntity(String id);
    Document getDocument(String id);
    void create(List<NamedEntity> neList);
    void create(Document document);

    // user related
    Set<User> getRecommendations(Project project);
    Set<User> getRecommendations(Project project, List<String> documentIds);

    // project related
    List<Document> getDocumentsNotTaggedWithPipeline(Project project, Pipeline.Type type);
    List<Document> getStarredDocuments(User user);
    List<String> getStarredDocuments(Project project, User user);
    Set<String> getRecommentationsBy(Project project, List<User> users);

    // standalone (to remove later ?)
    int star(Project project, User user, List<String> documentIds);
    int unstar(Project project, User user, List<String> documentIds);
    int recommend(Project project, User user, List<String> documentIds);
    int unrecommend(Project project, User user, List<String> documentIds);
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

    List<Note> getNotes(Project project);


}
