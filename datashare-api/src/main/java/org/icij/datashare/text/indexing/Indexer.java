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
import java.util.Map;
import java.util.stream.Stream;


public interface Indexer extends Closeable {
    Logger LOGGER = LoggerFactory.getLogger(Indexer.class);

    Searcher search(Class<? extends Entity> entityClass);

    void close();

    /**
     * Add document to index from JSON as a Map
     *
     * @param index the index store
     * @param type  tne index document type
     * @param id    the index document id
     * @param json  the document content source as JSON Map
     * @return true if successfully added; false otherwise
     */
    boolean add(String index, String type, String id, Map<String, Object> json);

    /**
     * {@link Indexer#add(String, String, String, Map)} with {@code parent}
     */
    boolean add(String index, String type, String id, Map<String, Object> json, String parent);

    boolean bulkAdd(Pipeline.Type nerType, List<NamedEntity> namedEntities, Document parent) throws IOException;

    /**
     * Add document to index from Object
     *
     * @param index the index store
     * @param obj   the Object instance to add
     * @param <T>   the obj instance type
     * @return true if successfully added; false otherwise
     */
    <T extends Entity> boolean add(String index, T obj);

    <T extends Entity> boolean add(T obj);

    <T extends Entity> T get(String id);
    <T extends Entity> T get(String id, String parent);

    interface Searcher {
        Searcher ofStatus(Document.Status indexed);
        Stream<? extends Entity> execute();
        Searcher withSource(String... fields);
        Searcher withSource(boolean source);
        Searcher without(Pipeline.Type... nlpPipelines);
        Searcher with(Pipeline.Type... nlpPipelines);
    }
}
