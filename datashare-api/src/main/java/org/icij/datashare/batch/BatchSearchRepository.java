package org.icij.datashare.batch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.user.User;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Collections.unmodifiableList;
import static org.icij.datashare.text.ProjectProxy.fromNameStringList;

public interface BatchSearchRepository extends Closeable {
    boolean save(BatchSearch batchSearch);
    boolean saveResults(String batchSearchId, String query, List<Document> documents);
    boolean saveResults(String batchSearchId, String query, List<Document> documents, boolean isFirstScroll);
    boolean setState(String batchSearchId, BatchSearch.State state);
    boolean setState(String batchSearchId, SearchException error);
    boolean deleteAll(User user);
    boolean delete(User user, String batchId);


    List<BatchSearchRecord> getRecords(User user, List<String> projectsIds);
    int getTotal(User user, List<String> projectsIds, WebQuery webQuery);
    List<BatchSearchRecord> getRecords(User user, List<String> projectsIds, WebQuery webQuery);
    List<String> getQueued();
    List<SearchResult> getResults(User user, String batchSearchId);
    List<SearchResult> getResults(User user, String batchId, WebQuery webQuery);
    int getResultsTotal(User user, String batchId, WebQuery webQuery);

    boolean publish(User user, String batchId, boolean published);

    BatchSearch get(String id);
    BatchSearch get(User user, String batchId);
    BatchSearch get(User user, String batchId, boolean withQueries);

    Map<String,Integer> getQueries(User user, String batchId, int from, int size, String search, String sort, String order);
    Map<String,Integer> getQueries(User user, String batchId, int from, int size, String search, String sort, String order, int maxResults);

    boolean reset(String batchId);
    @JsonIgnoreProperties(ignoreUnknown = true)
    class WebQuery extends WebQueryPagination {
        public static final String DEFAULT_SORT_FIELD = "doc_nb";
        public final String query;
        public final String field;
        public final List<String> queries;
        public final boolean queriesExcluded;
        public final List<String> contentTypes;

        public final List<ProjectProxy> project;
        public final List<String> batchDate;
        public final List<String> state;
        public final String publishState;
        public final boolean withQueries;

        @JsonCreator
        public WebQuery(@JsonProperty("size") int size, @JsonProperty("from") int from,
                        @JsonProperty("sort") String sort, @JsonProperty("order") String order,
                        @JsonProperty("query") String query, @JsonProperty("field") String field,
                        @JsonProperty("queries") List<String> queries,  @JsonProperty("project") List<String> project,
                        @JsonProperty("batchDate") List<String> batchDate, @JsonProperty("state") List<String> state,
                        @JsonProperty("publishState") String publishState, @JsonProperty("withQueries") boolean withQueries,
                        @JsonProperty("queriesExcluded") boolean queriesExcluded, @JsonProperty("contentTypes") List<String> contentTypes) {
            super(sort == null ? DEFAULT_SORT_FIELD : sort, sort == null ? "asc": order,from,size);
            this.query = query;
            this.field = field;
            this.queries = queries == null ? null: unmodifiableList(queries);
            this.queriesExcluded = queriesExcluded;
            this.contentTypes = contentTypes == null ? null: unmodifiableList(contentTypes);
            this.project = project == null ? null: unmodifiableList(fromNameStringList(project));
            this.batchDate = batchDate == null ? null: unmodifiableList(batchDate);
            this.state = state == null ? null: unmodifiableList(state);
            this.publishState = publishState;
            this.withQueries = withQueries;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            WebQuery that = (WebQuery) o;
            return super.equals(that) &&
                    Objects.equals(query,that.query) &&
                    Objects.equals(field,that.field) &&
                    Objects.equals(queries, that.queries) &&
                    Objects.equals(project, that.project) &&
                    Objects.equals(batchDate, that.batchDate) &&
                    Objects.equals(state, that.state) &&
                    Objects.equals(publishState, that.publishState) &&
                    Objects.equals(contentTypes, that.contentTypes) &&
                    Objects.equals(queriesExcluded, that.queriesExcluded);
        }

        @Override
        public int hashCode() { return Objects.hash(super.hashCode(), query, field, queries, project, batchDate, state, publishState, contentTypes, queriesExcluded); }
        public boolean hasFilteredContentTypes() { return contentTypes !=null && !contentTypes.isEmpty();}
        public boolean hasFilteredQueries() { return queries !=null && !queries.isEmpty();}
        public boolean hasFilteredProjects() { return project !=null && !project.isEmpty();}
        public boolean hasFilteredDates() { return batchDate !=null && !batchDate.isEmpty();}
        public boolean hasFilteredStates() { return state !=null && !state.isEmpty();}
        public boolean hasFilteredPublishStates() { return publishState !=null && !publishState.isEmpty();}
        public boolean isSorted() { return !DEFAULT_SORT_FIELD.equals(this.sort);}
    }
}
