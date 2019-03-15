package org.icij.datashare.text.indexing;

import org.icij.datashare.Entity;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;


public interface Indexer extends Closeable {
    Logger LOGGER = LoggerFactory.getLogger(Indexer.class);

    Searcher search(String indexName, Class<? extends Entity> entityClass);

    boolean createIndex(String indexName) throws IOException;
    boolean deleteAll(String indexName) throws IOException;

    void close() throws IOException;

    boolean bulkAdd(String indexName, Pipeline.Type nerType, List<NamedEntity> namedEntities, Document parent) throws IOException;
    <T extends Entity> boolean bulkUpdate(String indexName, List<? extends Entity> entities) throws IOException;
    <T extends Entity> void add(String indexName, T obj) throws IOException;
    <T extends Entity> void update(String indexName, T obj) throws IOException;

    <T extends Entity> T get(String indexName, String id);
    <T extends Entity> T get(String indexName, String id, String root);

    interface Searcher {
        Searcher ofStatus(Document.Status indexed);
        Stream<? extends Entity> execute() throws IOException;
        Searcher withSource(String... fields);
        Searcher withSource(boolean source);
        Searcher without(Pipeline.Type... nlpPipelines);
        Searcher with(Pipeline.Type... nlpPipelines);
        Searcher limit(int maxCount);
        Searcher withFieldValue(String key, String value);
    }
}
