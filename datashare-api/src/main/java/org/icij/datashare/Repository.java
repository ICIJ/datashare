package org.icij.datashare;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;

import java.util.*;

public interface Repository {
    NamedEntity getNamedEntity(String id);
    Document getDocument(String id);
    void create(List<NamedEntity> neList);
    void create(Document document);

    // user related
    AggregateList<User> getRecommendations(Project project);
    AggregateList<User> getRecommendations(Project project, List<String> documentIds);
    boolean addToUserHistory(List<Project> project, UserEvent userEvent);
    boolean renameSavedSearch(User user, int eventId, String newName);
    List<UserEvent> getUserHistory(User user, UserEvent.Type type, int from, int size, String sort, boolean desc, String... projectIds);
    List<UserEvent> getUserEvents(User user);
    int getUserHistorySize(User user, UserEvent.Type type, String... projectIds);
    boolean deleteUserHistory(User user, UserEvent.Type type);
    boolean deleteUserHistoryEvent(User user, int eventId);

    // project related
    List<Document> getDocumentsNotTaggedWithPipeline(Project project, Pipeline.Type type);
    List<Document> getStarredDocuments(User user);
    List<String> getStarredDocuments(Project project, User user);

    Set<String> getRecommendationsBy(Project project, List<User> users);

    // document user recommendations
    List<DocumentUserRecommendation> getDocumentUserRecommendations(int from, int size);
    List<DocumentUserRecommendation> getDocumentUserRecommendations(int from, int size, List<Project> projects);
    int recommend(Project project, User user, List<String> documentIds);
    int unrecommend(Project project, User user, List<String> documentIds);

    // standalone (to remove later ?)
    int star(Project project, User user, List<String> documentIds);
    int unstar(Project project, User user, List<String> documentIds);
    boolean tag(Project prj, String documentId, Tag... tags);
    boolean untag(Project prj, String documentId, Tag... tags);
    boolean tag(Project prj, List<String> documentIds, Tag... tags);
    boolean untag(Project prj, List<String> documentIds, Tag... tags);
    List<String> getDocuments(Project project, Tag... tags);
    List<Tag> getTags(Project project, String documentId);

    boolean deleteAll(String projectId);
    Project getProject(String projectId);
    List<Project> getProjects();
    List<Project> getProjects(List<String> projectIds);
    boolean save(Project project);

    List<Note> getNotes(Project prj, String pathPrefix);
    boolean save(Note note);

    List<Note> getNotes(Project project);

    boolean getHealth();

    boolean save(User user);
    User getUser(String userId);

    class AggregateList<T> {
        public final List<Aggregate<T>> aggregates;
        public final int totalCount;

        public AggregateList(List<Aggregate<T>> aggregates, int totalCount) {
            this.aggregates = aggregates;
            this.totalCount = totalCount;
        }
    }

    class Aggregate<T> {
        public final T item;
        public final int count;

        public Aggregate(T item, int count) {
            this.item = item;
            this.count = count;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Aggregate)) return false;
            Aggregate<?> aggregate = (Aggregate<?>) o;
            return count == aggregate.count &&
                    Objects.equals(item, aggregate.item);
        }
        @Override public int hashCode() { return Objects.hash(item, count);}
        @Override public String toString() { return item + "=" + count;}
    }
}
