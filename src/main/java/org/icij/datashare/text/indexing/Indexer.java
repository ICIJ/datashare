package org.icij.datashare.text.indexing;

import com.fasterxml.jackson.databind.JsonNode;
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

    Searcher search(List<String> indexesNames, Class<? extends Entity> entityClass);

    boolean createIndex(String indexName) throws IOException;
    boolean deleteAll(String indexName) throws IOException;

    boolean getHealth();

    void close() throws IOException;

    boolean bulkAdd(String indexName, Pipeline.Type nerType, List<NamedEntity> namedEntities, Document parent) throws IOException;
    <T extends Entity> boolean bulkAdd(final String indexName, List<T> entities) throws IOException;
    <T extends Entity> boolean bulkUpdate(String indexName, List<T> entities) throws IOException;
    <T extends Entity> void add(String indexName, T obj) throws IOException;
    <T extends Entity> void update(String indexName, T obj) throws IOException;

    <T extends Entity> T get(String indexName, String id);
    <T extends Entity> T get(String indexName, String id, String root);

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
        Searcher ofStatus(Document.Status indexed);
        Stream<? extends Entity> execute() throws IOException;
        Stream<? extends Entity> scroll() throws IOException;
        Stream<? extends Entity> scroll(int numSlice, int nbSlices) throws IOException;
        Searcher set(JsonNode jsonQuery);
        Searcher setFromTemplate(String jsonQueryTemplate, String query);
        Searcher withSource(String... fields);
        Searcher withoutSource(String... fields);
        Searcher withSource(boolean source);
        Searcher without(Pipeline.Type... nlpPipelines);
        Searcher with(Pipeline.Type... nlpPipelines);
        Searcher limit(int maxCount);
        void clearScroll() throws IOException;
        long totalHits();
        Searcher with(Tag... tags);
        Searcher with(String query);
        Searcher with(String query, int fuzziness, boolean phraseMatches);
        Searcher thatMatchesFieldValue(String key, Object value);
        Searcher withFieldValues(String key, String... values);
        Searcher withPrefixQuery(String key, String... values);
    }
}
