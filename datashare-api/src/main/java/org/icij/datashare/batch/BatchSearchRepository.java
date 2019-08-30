package org.icij.datashare.batch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.text.Document;
import org.icij.datashare.user.User;

import java.util.List;
import java.util.Objects;

import static java.util.Collections.unmodifiableList;

public interface BatchSearchRepository {
    boolean save(User user, BatchSearch batchSearch);
    boolean saveResults(String batchSearchId, String query, List<Document> documents);
    boolean setState(String batchSearchId, BatchSearch.State state);
    boolean deleteBatchSearches(User user);

    List<BatchSearch> get(User user);
    List<BatchSearch> getQueued();
    List<SearchResult> getResults(User user, String batchSearchId);
    List<SearchResult> getResults(User user, String batchId, WebQuery webQuery);
    BatchSearch get(User user, String batchId);

    @JsonIgnoreProperties(ignoreUnknown = true)
    class WebQuery {
        public final String sort;
        public final String order;
        public final int from;
        public final int size;
        public final List<String> queries;

        @JsonCreator
        public WebQuery(@JsonProperty("size") int size, @JsonProperty("from") int from,
                        @JsonProperty("sort") String sort, @JsonProperty("order") String order,
                        @JsonProperty("queries") List<String> queries) {
            this.size = size;
            this.from = from;
            this.sort = sort == null ? "doc_nb": sort;
            this.order = sort == null ? "asc": sort;
            this.queries = queries == null ? null: unmodifiableList(queries);
        }

        public WebQuery(int size, int from) {
            this(size, from, null, null, null);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            WebQuery that = (WebQuery) o;
            return from == that.from &&
                    size == that.size &&
                    Objects.equals(sort, that.sort) &&
                    Objects.equals(order, that.order) &&
                    Objects.equals(queries, that.queries);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sort, order, from, size, queries);
        }
        public boolean hasFilteredQueries() { return queries !=null && !queries.isEmpty();}
    }
}
