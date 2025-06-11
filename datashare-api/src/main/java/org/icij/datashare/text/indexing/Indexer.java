package org.icij.datashare.text.indexing;

import org.icij.datashare.Entity;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;


public interface Indexer extends Closeable {
    Logger LOGGER = LoggerFactory.getLogger(Indexer.class);

    QueryBuilderSearcher search(List<String> indexesNames, Class<? extends Entity> entityClass);
    Searcher search(List<String> indexesNames, Class<? extends Entity> entityClass, SearchQuery query);

    boolean createIndex(String indexName) throws IOException;
    boolean deleteAll(String indexName) throws IOException;

    boolean getHealth();
    boolean ping() throws IOException;
    void close() throws IOException;

    boolean bulkAdd(String indexName, Pipeline.Type nerType, List<NamedEntity> namedEntities, Document parent) throws IOException;
    <T extends Entity> boolean bulkAdd(final String indexName, List<T> entities) throws IOException;
    <T extends Entity> boolean bulkUpdate(String indexName, List<T> entities) throws IOException;
    <T extends Entity> void add(String indexName, T obj) throws IOException;
    <T extends Entity> void update(String indexName, T obj) throws IOException;

    boolean exists(String indexName) throws IOException;
    boolean exists(String indexName, String id) throws IOException;

    <T extends Entity> T get(String indexName, String id);
    <T extends Entity> T get(String indexName, String id, List<String> sourceExcludes);
    <T extends Entity> T get(String indexName, String id, String root);
    <T extends Entity> T get(String indexName, String id, String root, List<String> sourceExcludes);

    String executeRaw(String method, String url, String body) throws IOException;

    // from Repository
    boolean tag(Project prj, String documentId, String rootDocument, Tag... tags) throws IOException;
    boolean untag(Project prj, String documentId, String rootDocument, Tag... tags) throws IOException;
    boolean tag(Project prj, List<String> documentIds, Tag... tags) throws IOException;
    boolean untag(Project prj, List<String> documentIds, Tag... tags) throws IOException;
    ExtractedText getExtractedText(String indexName, String documentId, String rootDocument, int offset, int limit, String targetLanguage) throws IOException;
    SearchedText searchTextOccurrences(String indexName, String documentId, String query, String targetLanguage) throws IOException;
    SearchedText searchTextOccurrences(String indexName, String documentId, String rootDocument, String query, String targetLanguage) throws IOException;

    interface Searcher {
        Stream<? extends Entity> execute() throws IOException;
        Stream<? extends Entity> execute(String stringQuery) throws IOException;
        Stream<? extends Entity> scroll(String duration) throws IOException;
        Stream<? extends Entity> scroll(String duration, String stringQuery) throws IOException;
        Stream<? extends Entity> scroll(ScrollQuery scrollQuery) throws IOException;
        Searcher withSource(String... fields);
        Searcher withoutSource(String... fields);
        Searcher withSource(boolean source);
        Searcher limit(int maxCount);
        Searcher sort(String field, SortOrder order);
        void clearScroll() throws IOException;
        long totalHits();
        Searcher with(int fuzziness, boolean phraseMatches);
        enum SortOrder { ASC, DESC }
    }

    interface QueryBuilderSearcher extends Searcher {
        QueryBuilderSearcher ofStatus(Document.Status indexed);
        QueryBuilderSearcher without(Pipeline.Type... nlpPipelines);
        QueryBuilderSearcher with(Pipeline.Type... nlpPipelines);
        QueryBuilderSearcher with(Tag... tags);
        QueryBuilderSearcher thatMatchesFieldValue(String key, Object value);
        QueryBuilderSearcher withFieldValues(String key, String... values);
        QueryBuilderSearcher withPrefixQuery(String key, String... values);
    }

    class ScrollQuery {
        private final String duration;
        private final int numSlice;
        private final int nbSlices;
        private final String stringQuery;
        public ScrollQuery(String duration, int numSlice, int nbSlices, String stringQuery) {
            this.duration = duration;
            this.numSlice = numSlice;
            this.nbSlices = nbSlices;
            this.stringQuery = stringQuery;
        }

        public String getDuration() {
            return duration;
        }

        public int getNumSlice() {
            return numSlice;
        }

        public int getNbSlices() {
            return nbSlices;
        }

        public String getStringQuery() {
            return stringQuery;
        }
    }
}
