package org.icij.datashare.text.indexing;

import org.icij.datashare.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.icij.datashare.function.ThrowingFunctions.joinComma;


public interface Indexer extends Closeable {
    Logger LOGGER = LoggerFactory.getLogger(Indexer.class);

    enum NodeType {
        LOCAL ("localhost",   1, 0),
        REMOTE("kc.icij.org", 8, 2);

        private final String defaultHost;
        private final int    defaultIndexShards;
        private final int    defaultIndexReplicas;

        NodeType(final String host, final int indexShards, final int indexReplicas) {
            this.defaultHost          = host;
            this.defaultIndexShards   = indexShards;
            this.defaultIndexReplicas = indexReplicas;
        }

        public static Optional<NodeType> parse(final String nodeType) {
            if (nodeType == null || nodeType.isEmpty())
                return Optional.empty();
            try {
                return Optional.of(valueOf(nodeType.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    enum Property {
        NODE_TYPE,
        HOSTS,
        PORTS,
        CLUSTER,
        SHARDS,
        REPLICAS,
        INDEX_TYPE,
        INDEX_JOIN_FIELD,
        DOC_TYPE_FIELD;

        public String getName() {
            return name().toLowerCase().replace('_', '-');
        }

        public static Function<Indexer.NodeType, Function<List<String>, Function<List<Integer>, Properties>>>
                build =
                indexerNodeType -> indexerHostnames -> indexerHostports -> {
                    Properties properties = new Properties();
                    properties.setProperty(NODE_TYPE.getName(), indexerNodeType.toString());
                    properties.setProperty(HOSTS.getName(),     joinComma.apply(indexerHostnames));
                    properties.setProperty(PORTS.getName(),     joinComma.apply(indexerHostports));
                    return properties;
                };
    }

    /**
     * Close connection to index node(s)
     */
    void close();

    /**
     * Blocking until index is up or timed out
     * @param index the index to wait for
     * @return true if index is up and running; false otherwise
     */
    boolean awaitIndexIsUp(String index);

    /**
     * Create a new index store
     *
     * @param index the name of new index
     * @return true if successfully created; false otherwise
     */
    boolean createIndex(final String index);

    /**
     * Delete an index store
     *
     * @param index the name of index to be deleted
     * @return true if successfully deleted; false otherwise
     */
    boolean deleteIndex(String index);

    /**
     * @return the list of all existing indices
     */
    List<String> getIndices();

    /**
     * Refresh specified indices to make changes accessible to search
     *
     * @param indices the list of indices to refresh
     * @return true if refreshed all shards successfully; false otherwise
     */
    boolean refreshIndices(String... indices);

    /**
     * Commit indices changes
     *
     * @param indices the list of indices to commit
     * @return true if committed all shards successfully; false otherwise
     */
    boolean commitIndices(String... indices);

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

    /**
     * Add document to index from Object
     *
     * @param index the index store
     * @param obj   the Object instance to add
     * @param <T>   the obj instance type
     * @return true if successfully added; false otherwise
     */
    <T extends Entity> boolean add(String index, T obj);

    /**
     * Add an document indexing to batch processing
     * document source given as a JSON Map
     */
    void addBatch(String index, String type, String id, Map<String, Object> json);

    /**
     * Add a document indexing, with parent, to batch processing
     * document source given as a JSON Map
     */
    void addBatch(String index, String type, String id, Map<String, Object> json, String parent);

    /**
     * Delete document {@code index} / {@code type} / {@code id}
     *
     * @param index the index store
     * @param type  the index document type
     * @param id    the index document id
     * @return true if document successfully deleted; false otherwise
     */
    boolean delete(String index, String type, String id);

    /**
     * {@link Indexer#delete(String, String, String)} with {@code parent}
     */
    boolean delete(String index, String type, String id, String parent);

    /**
     * Add a document deletion to batch processing
     * document's id given as a String
     */
    void batchDelete(String index, String type, String id);

    /**
     * Add a document deletion, with parent, to batch processing
     * document's id given as a String
     */
    void batchDelete(String index, String type, String id, String parent);

    /**
     * Add a document deletion to batch processing
     * document's id given in {@code obj}
     */
    <T extends Entity> void batchDelete(String index, T obj);

    /**
     * Get documents matching {@code query}, any type in any index
     *
     * @param query the string query to match
     * @return the stream of search results as JSON Maps
     */
    Stream<Map<String, Object>> search(String query);
    Stream<Map<String, Object>> search(String query, int from, int to);


    /**
     * Get documents matching {@code query} and {@code type} in given {@code indices}
     *
     * @param query   the string query to match
     * @param type    the index document type
     * @param indices the list of indices in which to search
     * @return the stream of search results as JSON Maps
     */
    Stream<Map<String, Object>> search(String query, String type, String... indices);
    Stream<Map<String, Object>> search(String query, int from, int to, String type, String... indices);

}
