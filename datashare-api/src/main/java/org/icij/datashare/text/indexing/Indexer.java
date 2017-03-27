package org.icij.datashare.text.indexing;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import static java.util.Arrays.asList;
import java.io.Closeable;
import java.lang.reflect.InvocationTargetException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.icij.datashare.Entity;
import org.icij.datashare.reflect.EnumTypeToken;
import static org.icij.datashare.text.indexing.Indexer.NodeType.LOCAL;
import static org.icij.datashare.text.indexing.Indexer.Type.ELASTICSEARCH;
import static org.icij.datashare.function.ThrowingFunctions.joinComma;


/**
 * Store and search documents on cluster of index node(s)
 *
 * Created by julien on 6/13/16.
 */
public interface Indexer extends Closeable {

    enum Type implements EnumTypeToken {
        ELASTICSEARCH(9300);

        private final String className;
        private final int defaultPort;

        Type(int port) {
            className = buildClassName(Indexer.class, this);
            defaultPort = port;
        }

        @Override
        public String getClassName() { return className; }

        public int defaultPort() {
            return defaultPort;
        }

        public static Optional<Type> parse(final String valueName) {
            return EnumTypeToken.parse(Type.class, valueName);
        }

        public static Optional<Type> fromClassName(final String className) {
            return EnumTypeToken.parseClassName(Indexer.class, Type.class, className);
        }
    }

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

        public String defaultHost() {
            return defaultHost;
        }
        public int defaultIndexReplicas() {
            return defaultIndexReplicas;
        }
        public int defaultIndexShards() {
            return defaultIndexShards;
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
        REPLICAS;

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


    Type DEFAULT_TYPE = ELASTICSEARCH;

    NodeType DEFAULT_NODETYPE = LOCAL;

    String DEFAULT_CLUSTER = "datashare";


    /**
     * Instantiate a concrete {@code Indexer} reflectively with {@link Type}
     *
     * Defaults to {@link NodeType#LOCAL} index node(s)
     *
     * @param type       the {@link Type} enum value denoting an {@code Indexer} implementation
     * @param properties the {@code Indexer} settings as Properties
     * @return Indexer Optional if instantiation instance if succeeded; empty Optional otherwise
     * @see Type
     * @see EnumTypeToken
     * @see Property
     */
    static Optional<Indexer> create(Type type, Properties properties) {
        String interfaceName = Indexer.class.getName();
        Logger logger = LogManager.getLogger(Indexer.class);
        if ( ! asList(Type.values()).contains(type)) {
            logger.error("Unknown " + interfaceName + " " + type);
            return Optional.empty();
        }
        try {
            Object indexerInstance = Class.forName( type.getClassName() )
                    .getDeclaredConstructor( new Class[]{Properties.class} )
                    .newInstance           (             properties        );
            return Optional.of( (Indexer) indexerInstance );
        } catch (ClassNotFoundException e) {
            logger.error(type + " " + interfaceName + " not found in classpath.", e);
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument values for " + type + " " + interfaceName, e);
            return Optional.empty();
        } catch (InvocationTargetException e) {
            logger.error("Failed to instantiate " + type + " " + interfaceName, e.getCause());
            return Optional.empty();
        }catch (InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            logger.error("Failed to instantiate " + type + " " + interfaceName, e);
            return Optional.empty();
        }
    }

    /**
     * Instantiate a concrete {@code Indexer} of {@link Indexer#DEFAULT_TYPE}
     *
     * @param properties the indexer settings as Properties
     * @return Indexer Optional if instantiation instance if succeeded; empty Optional otherwise
     * @see Type
     * @see EnumTypeToken
     * @see Property
     */
    static Optional<Indexer> create(Properties properties) {
        return create(DEFAULT_TYPE, properties);
    }

    /**
     * Instantiate a default concrete {@code Indexer} with default settings
     *
     * @return Indexer Optional if instantiation instance if succeeded; empty Optional otherwise
     */
    static Optional<Indexer> create() {
        return create(new Properties());
    }


    Type getType();


    /**
     * Close connection to index node(s)
     */
    void close();


    /**
     * @return the default port for {@code nodeType} index node(s)
     */
    default int defaultPort() {
        return getType().defaultPort();
    }

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
     * Add document to index from JSON as a String
     *
     * @param index the index store
     * @param type  tne index document type
     * @param id    the index document id
     * @param json  the document content source as JSON String
     * @return true if successfully added; false otherwise
     */
    boolean add(String index, String type, String id, String json);

    /**
     * {@link Indexer#add(String, String, String, String)} with {@code parent}
     */
    boolean add(String index, String type, String id, String json, String parent);

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
     * Add a document indexing to batch processing
     * document source given as a JSON String
     */
    void batchAdd(String index, String type, String id, String json);

    /**
     * Add a document indexing, with parent, to batch processing
     * document source given as a JSON String
     */
    void batchAdd(String index, String type, String id, String json, String parent);

    /**
     * Add an document indexing to batch processing
     * document source given as a JSON Map
     */
    void batchAdd(String index, String type, String id, Map<String, Object> json);

    /**
     * Add a document indexing, with parent, to batch processing
     * document source given as a JSON Map
     */
    void batchAdd(String index, String type, String id, Map<String, Object> json, String parent);

    /**
     * Add a document indexing, with parent, to batch processing
     * document source given as an Object of type {@code T}
     */
    <T extends Entity> void batchAdd(String index, T obj);


    /**
     * Get document by id in index
     *
     * @param index the index store
     * @param type  tne index document type
     * @param id    the index document id
     * @return true if successfully added; false otherwise
     */
    Map<String, Object> read(String index, String type, String id);

    /**
     * {@link Indexer#read(String, String, String)} with {@code parent}
     */
    Map<String, Object> read(String index, String type, String id, String parent);

    /**
     * Get document by id in index type given by {@code Class}
     *
     * @param index the index store
     * @param cls   the class holding the type
     * @param id    the index document id
     * @param <T>   the type of result object
     * @return the index document reified as an object of type T if it exists in index; null otherwise
     */
    <T extends Entity> T read(String index, Class<T> cls, String id);

    /**
     * {@link Indexer#read(String, Class, String)} with {@code parent}
     */
    <T extends Entity> T read(String index, Class<T> cls, String id, String parent);

    /**
     * Get document by id in index
     *
     * @param index the index store
     * @param obj   the object instance holding type and id
     * @param <T>   the type of result object
     * @return the index document reified as an object of type T if it exists in index; null otherwise
     */
    <T extends Entity> T read(String index, T obj);


    /**
     * Update document matching {@code index} / {@code type} / {@code id}
     *
     * @param index the index store
     * @param type  the index document type
     * @param id    the index document id
     * @param json  the field-value source pairs for update
     * @return true if successfully updated; false otherwise
     */
    boolean update(String index, String type, String id, String json);

    /**
     * Update document matching {@code index} / {@code type} / {@code id}
     *
     * @param index the index store
     * @param type  the index document type
     * @param id    the index document id
     * @param json  the field-value source pairs for update
     * @return true if successfully updated; false otherwise
     */
    boolean update(String index, String type, String id, Map<String, Object> json);

    /**
     * {@link Indexer#update(String, String, String, Map)} with {@code parent}
     */
    boolean update(String index, String type, String id, Map<String, Object> json, String parent);

    /**
     * Update document matching {@code index}; type and id from {@code obj}
     *
     * @param index the index store
     * @param obj   the source object instance for update
     * @param <T>   the obj instance type
     * @return true if actually updated; false otherwise
     */
    <T extends Entity> boolean update(String index, T obj);


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
     * Delete document from {@code index}; type and id from {@code obj}
     *
     * @param index the index store
     * @param obj   the object instance from which to extract document index type and id
     * @param <T>   the obj instance type
     * @return true if document successfully deleted; false otherwise
     */
    <T extends Entity> boolean delete(String index, T obj);

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
     * Get documents matching {@code query} and {@code type} in any index
     *
     * @param query the string query to match
     * @param type  the index document type
     * @return the stream of search results as JSON Maps
     */
    Stream<Map<String, Object>> search(String query, String type);

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


    /**
     * Get documents matching {@code query} and type (from {@code cls}) in any {@code index}
     *
     * @param query the string query to match
     * @param cls   the class of result objects
     * @param <T>   the type of result objects
     * @return the stream of search results as type T instances
     */
    <T extends Entity> Stream<T> search(String query, Class<T> cls);

    /**
     * Get documents matching {@code query} and type (from {@code cls}) in given {@code indices}
     *
     * @param query   the string query to match
     * @param cls     the class holding the type
     * @param indices the list of indices in which to search
     * @param <T>     the type of result objects
     * @return the stream of search results as type T instances
     */
    <T extends Entity> Stream<T> search(String query, Class<T> cls, String... indices);


    /**
     * Get all documents in given {@code indices}
     *
     * @param indices the list of indices in which to search
     * @return the stream of search results as JSON Maps
     */
    Stream<Map<String, Object>> searchIndices(String... indices);

    /**
     * Get all documents in given {@code indices} of given type (from {@code cls})
     *
     * @param cls     the class holding the type
     * @param indices the list of indices in which to search
     * @return the stream of search results as JSON Maps
     */
    <T extends Entity> Stream<T> searchIndices(Class<T> cls, String... indices);

    /**
     * Get all documents matching one of given {@code types}
     *
     * @param types the list of document types to search
     * @return the stream of search results as JSON Maps
     */
    Stream<Map<String, Object>> searchTypes(String... types);

    /**
     * Get all documents matching given type (from {@code cls})
     *
     * @param cls the class holding the type
     * @return the stream of search results as JSON Maps
     */
    <T extends Entity> Stream<T> searchTypes(Class<T> cls);


    /**
     * Get parent documents from their children
     *
     * @param childType  the children's type
     * @param parentType the parents' type
     * @return the stream of search results as JSON Maps
     */
    Stream<Map<String, Object>> searchHasChild(String parentType, String childType);

    Stream<Map<String, Object>> searchHasChild(String parentType, String childType, String query);

    <T extends Entity, U extends Entity> Stream<T> searchHasChild(Class<T> parentCls, Class<U> childCls);

    <T extends Entity, U extends Entity> Stream<T> searchHasChild(Class<T> parentCls, Class<U> childCls, String query);

    <T extends Entity, U extends Entity> Stream<T> searchHasNoChild(Class<T> parentCls, Class<U> childCls);

    <T extends Entity, U extends Entity> Stream<T> searchHasNoChild(Class<T> parentCls, Class<U> childCls, String query);

    /**
     * Get child documents from their parents
     *
     * @param childType  the children's type
     * @param parentType the parents' type
     * @return the stream of search results as JSON Maps
     */
    Stream<Map<String, Object>> searchHasParent(String childType, String parentType);

    /**
     * {@link Indexer#searchHasParent(String, String)} satisfying {@code query}
     */
    Stream<Map<String, Object>> searchHasParent(String childType, String parentType, String query);

    <T extends Entity> Stream<T> searchHasParent(Class<T> childCls, String parentType);

    <T extends Entity> Stream<T> searchHasParent(Class<T> childCls, String parentType, String query);

    <T extends Entity> Stream<T> searchHasNoParent(Class<T> childCls, String parentType);

    <T extends Entity> Stream<T> searchHasNoParent(Class<T> childCls, String parentType, String query);

}
