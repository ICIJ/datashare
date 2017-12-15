package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.admin.indices.flush.FlushResponse;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.icij.datashare.Entity;
import org.icij.datashare.function.Pair;
import org.icij.datashare.function.ThrowingConsumer;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.indexing.AbstractIndexer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.net.InetAddress.getByName;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.elasticsearch.cluster.health.ClusterHealthStatus.GREEN;
import static org.elasticsearch.cluster.health.ClusterHealthStatus.YELLOW;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.icij.datashare.function.Functions.zip;
import static org.icij.datashare.text.indexing.Indexer.NodeType.LOCAL;
import static org.icij.datashare.text.indexing.Indexer.NodeType.REMOTE;


/**
 * {@link org.icij.datashare.text.indexing.Indexer}
 * {@link org.icij.datashare.text.indexing.AbstractIndexer}
 * {@link Type#ELASTICSEARCH}
 *
 * <a href="https://www.elastic.co/products/elasticsearch">Elasticsearch v5.4.1</a>
 *
 * Created by julien on 6/15/16.
 */
public final class ElasticsearchIndexer extends AbstractIndexer {
    
    public static final String VERSION = "5.4.1";

    public static final Path HOME = Paths.get( System.getProperty("user.dir"), "opt", "elasticsearch-" + VERSION);

    private static final int INDEX_MAX_RESULT_WINDOW = 100000;
    
    private static final int           BULKPROCESSOR_FLUSH_ACTIONS   = 2000;
    private static final ByteSizeValue BULKPROCESSOR_FLUSH_SIZE      = new ByteSizeValue(5, ByteSizeUnit.MB);
    private static final TimeValue     BULKPROCESSOR_FLUSH_TIME      = timeValueSeconds(20);
    private static final int           BULKPROCESSOR_CONCURRENT_REQS = 1;

    private static final Map<NodeType, ClusterHealthStatus> CLUSTER_UP_STATUS =
            new HashMap<NodeType, ClusterHealthStatus>() {{
                put(LOCAL,  YELLOW);
                put(REMOTE, GREEN);
            }};


    // Index Client service
    private final Client client;

    // Index Batch processor
    private final BulkProcessor bulkProcessor;


    public ElasticsearchIndexer(Properties properties) {
        super(properties);

        Settings settings = getNodeSettings(nodeType);
        LOGGER.info("Opening connection to "+ "[" + nodeType + "]" + " node(s)");
        LOGGER.info("Settings :\n" + settings.toDelimitedString('\n'));

        TransportClient               trnspClient = new PreBuiltTransportClient(settings);
        Stream<Pair<String, Integer>> trnspAddrs  = zip(hosts.stream(), ports.stream(), Pair::new);
        ThrowingConsumer<Pair<String, Integer>> addTrnspAddrToClient = addr ->
                trnspClient.addTransportAddress(new InetSocketTransportAddress(getByName(addr._1()), addr._2()));
        trnspAddrs.forEach( addTrnspAddrToClient );
        client = trnspClient;

        if ( ! awaitConnectionIsUp() ) {
            throw new RuntimeException("Failed to connect");
        }

        bulkProcessor = buildBulkProcessor();
    }


    @Override
    public void close() {
        LOGGER.info("Closing Elasticsearch connection");
        try {
            LOGGER.info("Awaiting bulk processor termination...");
            bulkProcessor.awaitClose(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            LOGGER.error("Failed to close batch processor", e);
        }
        client.close();
        LOGGER.info("Elasticsearch connection closed");
    }


    // __________ Admin indices __________

    @Override
    public boolean createIndex(String index) {
        try {
            final CreateIndexRequest  req  = new CreateIndexRequest(index).settings(getIndexSettings());
            final CreateIndexResponse resp = client.admin()
                    .indices()
                    .create(req)
                    .get();
            refreshIndices(index);
            awaitIndexIsUp(index);
            return resp.isAcknowledged();

        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to create index " + index, e);
            return false;
        }
    }

    @Override
    public List<String> getIndices() {
        try {
            final ClusterStateRequest req = new ClusterStateRequest();
            final ImmutableOpenMap<String, IndexMetaData> indicesMap = client.admin().cluster()
                    .state(req)
                    .get()
                    .getState()
                    .getMetaData()
                    .getIndices();
            List<String> indices = new ArrayList<>();
            indicesMap.keysIt().forEachRemaining( indices::add );
            return indices;
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to get indices", e);
            return emptyList();
        }
    }

    @Override
    public boolean commitIndices(String... indices) {
        try {
            final FlushRequest   req = new FlushRequest(indices);
            final FlushResponse resp = client.admin()
                    .indices()
                    .flush(req)
                    .get();
            return resp.getFailedShards() > 1;
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to flush indices " + String.join(", ", asList(indices)),e);
            return false;
        }
    }

    @Override
    public boolean deleteIndex(String index) {
        try {
            final DeleteIndexRequest  req  = new DeleteIndexRequest(index);
            final DeleteIndexResponse resp = client.admin()
                    .indices()
                    .delete(req)
                    .get();
            return resp.isAcknowledged();
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to delete index " + index, e);
            return false;
        }
    }

    @Override
    public boolean refreshIndices(String... indices) {
        try {
            final RefreshRequest  req  = new RefreshRequest(indices);
            final RefreshResponse resp = client.admin()
                    .indices()
                    .refresh(req)
                    .get();
            return resp.getFailedShards() > 1;
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to refresh indices " + String.join(", ", asList(indices)), e);
            return false;
        }
    }


    // __________  Add Document __________

    @Override
    public boolean add(String index, String type, String id, String json) {
        try {
            final IndexRequest  req  = new IndexRequest(index, type, id).source(json);
            final IndexResponse resp = client.index(req).get();
            return asList(RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to add doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }


    @Override
    public boolean add(String index, String type, String id, String json, String parent) {
        try {
            final IndexRequest  req  = new IndexRequest(index, type, id).source(json).parent(parent);
            final IndexResponse resp = client.index(req).get();
            return asList(RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to add doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public boolean add(String index, String type, String id, Map<String, Object> json) {
        try {
            final IndexRequest  req  = new IndexRequest(index, type, id).source(json);
            final IndexResponse resp = client.index(req).get();
            return asList(RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to add doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public boolean add(String index, String type, String id, Map<String, Object> json, String parent) {
        try {
            final IndexRequest  req  = new IndexRequest(index, type, id).source(json).parent(parent);
            final IndexResponse resp = client.index(req).get();
            return asList(RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to add doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public <T extends Entity> boolean add(String index, T obj) {
        final String              type   = JsonObjectMapper.getType(obj);
        final String              id     = JsonObjectMapper.getId(obj);
        final Map<String, Object> json   = JsonObjectMapper.getJson(obj);
        final Optional<String>    parent = JsonObjectMapper.getParent(obj);
        if (parent.isPresent())
            return add(index, type, id, json, parent.get());
        return add(index, type, id, json);
    }

    // __________ Batched Add Document(s) __________

    @Override
    public void addBatch(String index, String type, String id, String json) {
        bulkProcessor.add( new IndexRequest(index, type, id).source(json) );
    }

    @Override
    public void addBatch(String index, String type, String id, String json, String parent) {
        bulkProcessor.add( new IndexRequest(index, type, id).source(json).parent(parent) );
    }

    @Override
    public void addBatch(String index, String type, String id, Map<String, Object> json) {
        bulkProcessor.add( new IndexRequest(index, type, id).source(json) );
    }

    @Override
    public void addBatch(String index, String type, String id, Map<String, Object> json, String parent) {
        bulkProcessor.add( new IndexRequest(index, type, id).source(json).parent(parent) );
    }

    @Override
    public <T extends Entity> void addBatch(String index, T obj) {
        final String              type   = JsonObjectMapper.getType(obj);
        final String              id     = JsonObjectMapper.getId(obj);
        final Map<String, Object> json   = JsonObjectMapper.getJson(obj);
        final Optional<String>    parent = JsonObjectMapper.getParent(obj);
        if (parent.isPresent())
            addBatch(index, type, id, json, parent.get());
        else
            addBatch(index, type, id, json);
    }


    // __________ Read Document __________

    @Override
    public Map<String, Object> read(String index, String type, String id) {
        try {
            final GetRequest  req  = new GetRequest(index, type, id);
            final GetResponse resp = client.get(req).get();
            return resp.getSourceAsMap();
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to poll doc " + id + " of type " + type + " in index " + index, e);
            return emptyMap();
        }
    }

    @Override
    public Map<String, Object> read(String index, String type, String id, String parent) {
        try {
            final GetRequest  req  = new GetRequest(index, type, id).parent(parent);
            final GetResponse resp = client.get(req).get();
            return resp.getSourceAsMap();
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to get doc " + id + " of type " + type + " in index " + index + " with parent " + parent, e);
            return emptyMap();
        }
    }

    @Override
    public <T extends Entity> T read(String index, Class<T> cls, String id) {
        final String              type = JsonObjectMapper.getType(cls);
        final Map<String, Object> json = read(index, type, id);
        return JsonObjectMapper.getObject(id, json, cls);
    }

    @Override
    public <T extends Entity> T read(String index, Class<T> cls, String id, String parent) {
        final String              type = JsonObjectMapper.getType(cls);
        final Map<String, Object> json = read(index, type, id, parent);
        return JsonObjectMapper.getObject(id, json, cls);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Entity> T read(String index, T obj) {
        final String           type   = JsonObjectMapper.getType(obj);
        final String           id     = JsonObjectMapper.getId(obj);
        final Optional<String> parent = JsonObjectMapper.getParent(obj);
        Map<String, Object> json = parent.isPresent() ? read(index, type, id, parent.get()) : read(index, type, id);
        return JsonObjectMapper.getObject(id, json, (Class<T>) obj.getClass());
    }


    // __________ Delete Document __________

    @Override
    public boolean delete(String index, String type, String id) {
        try {
            final DeleteRequest  req  = new DeleteRequest(index, type, id);
            final DeleteResponse resp = client.delete(req).get();
            return asList(RestStatus.FOUND, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to delete doc " + id  + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public boolean delete(String index, String type, String id, String parent) {
        try {
            final DeleteRequest  req  = new DeleteRequest(index, type, id).parent(parent);
            final DeleteResponse resp = client.delete(req).get();
            return asList(RestStatus.FOUND, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to delete doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public <T extends Entity> boolean delete(String index, T obj) {
        final String           type   = JsonObjectMapper.getType(obj);
        final String           id     = JsonObjectMapper.getId(obj);
        final Optional<String> parent = JsonObjectMapper.getParent(obj);
        if (parent.isPresent())
            return delete(index, type, id, parent.get());
        return delete(index, type, id);
    }

    // __________ Batched Delete Document(s) __________

    @Override
    public void batchDelete(String index, String type, String id) {
        bulkProcessor.add( new DeleteRequest(index, type, id) );
    }

    @Override
    public void batchDelete(String index, String type, String id, String parent) {
        bulkProcessor.add( new DeleteRequest(index, type, id).parent(parent) );
    }

    @Override
    public <T extends Entity> void batchDelete(String index, T obj) {
        final String           type   = JsonObjectMapper.getType(obj);
        final String           id     = JsonObjectMapper.getId(obj);
        final Optional<String> parent = JsonObjectMapper.getParent(obj);
        if (parent.isPresent())
            batchDelete(index, type, id, parent.get());
        else
            batchDelete(index, type, id);
    }


    // __________ Update Document __________

    @Override
    public boolean update(String index, String type, String id, String json) {
        try {
            final UpdateRequest  req  = new UpdateRequest(index, type, id).doc(json);
            final UpdateResponse resp = client.update(req).get();
            return asList(RestStatus.FOUND, RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to update doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public boolean update(String index, String type, String id, Map<String, Object> json) {
        try {
            final UpdateRequest  req  = new UpdateRequest(index, type, id).doc(json);
            final UpdateResponse resp = client.update(req).get();
            return asList(RestStatus.FOUND, RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to update doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public boolean update(String index, String type, String id, Map<String, Object> json, String parent) {
        try {
            final UpdateRequest  req  = new UpdateRequest(index, type, id).doc(json).parent(parent);
            final UpdateResponse resp = client.update(req).get();
            return asList(RestStatus.FOUND, RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to update doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public <T extends Entity> boolean update(String index, T obj) {
        final String              type   = JsonObjectMapper.getType(obj);
        final String              id     = JsonObjectMapper.getId(obj);
        final Map<String, Object> json   = JsonObjectMapper.getJson(obj);
        final Optional<String>    parent = JsonObjectMapper.getParent(obj);
        if (parent.isPresent())
            return update(index, type, id, json, parent.get());
        return update(index, type, id, json);
    }


    // __________ Search Document(s) __________

    @Override
    public Stream<Map<String, Object>> search(String query) {
        return search( queryStringQuery(query) );
    }

    @Override
    public Stream<Map<String, Object>> search(String query, int from, int size) {
        return search(queryStringQuery(query), from, size);
    }

    @Override
    public Stream<Map<String, Object>> search(String query, String type) {
        return search( queryStringQuery(query), type );
    }

    @Override
    public <T extends Entity> Stream<T> search(String query, Class<T> cls) {
        return search( queryStringQuery(query), cls);
    }

    @Override
    public Stream<Map<String, Object>> search(String query, String type, String... indices) {
        return search( queryStringQuery(query), type, indices);
    }

    @Override
    public Stream<Map<String, Object>> search(String query, int from, int to, String type, String... indices) {
        return search( queryStringQuery(query), from, to, type, indices);
    }

    @Override
    public <T extends Entity> Stream<T> search(String query, Class<T> cls, String... indices) {
        return search( queryStringQuery(query), cls, indices);
    }


    @Override
    public Stream<Map<String, Object>> searchIndices(String... indices) {
        return resultStream( searchRequest().setIndices( indices ));
    }

    @Override
    public <T extends Entity> Stream<T> searchIndices(Class<T> cls, String... indices) {
        final String type = JsonObjectMapper.getType(cls);
        return resultStream( cls, searchRequest().setTypes( type ).setIndices( indices ));
    }


    @Override
    public Stream<Map<String, Object>> searchTypes(String... types) {
        return resultStream( searchRequest().setTypes( types ) );
    }

    @Override
    public <T extends Entity> Stream<T> searchTypes(Class<T> cls) {
        final String type = JsonObjectMapper.getType(cls);
        return resultStream( cls, searchRequest().setTypes( type ));
    }


    // __________ Search Document(s) with(out) children __________

    @Override
    public Stream<Map<String, Object>> searchHasChild(String parentType, String childType) {
        return search( mustHasChildQuery(childType), parentType);
    }

    @Override
    public Stream<Map<String, Object>> searchHasChild(String parentType, String childType, String query) {
        return search( mustHasChildQuery(childType, query), parentType);
    }

    @Override
    public <T extends Entity, U extends Entity> Stream<T> searchHasChild(Class<T> parentCls, Class<U> childCls) {
        String childType = JsonObjectMapper.getType(childCls);
        return search( mustHasChildQuery(childType), parentCls);
    }

    @Override
    public <T extends Entity, U extends Entity> Stream<T> searchHasChild(Class<T> parentCls,
                                                                         Class<U> childCls,
                                                                         String query) {
        String childType = JsonObjectMapper.getType(childCls);
        return search( mustHasChildQuery(childType, query), parentCls);
    }

    @Override
    public <T extends Entity, U extends Entity> Stream<T> searchHasNoChild(Class<T> parentCls,
                                                                           Class<U> childCls,
                                                                           String query) {
        String childType = JsonObjectMapper.getType(childCls);
        return search( mustNotHasChildQuery(childType, query), parentCls);
    }

    @Override
    public <T extends Entity, U extends Entity> Stream<T> searchHasNoChild(Class<T> parentCls, Class<U> childCls) {
        String childType = JsonObjectMapper.getType(childCls);
        return search( mustNotHasChildQuery(childType), parentCls );
    }


    // __________ Search Document(s) with (no) parent __________

    @Override
    public Stream<Map<String, Object>> searchHasParent(String childType, String parentType) {
        return search( mustHasParentQuery(parentType), childType);
    }

    @Override
    public Stream<Map<String, Object>> searchHasParent(String childType, String parentType, String query) {
        return search( mustHasParentQuery(parentType, query), childType);
    }

    @Override
    public <T extends Entity> Stream<T> searchHasParent(Class<T> childCls, String parentType) {
        return search( mustHasParentQuery(parentType), childCls);
    }

    @Override
    public <T extends Entity> Stream<T> searchHasParent(Class<T> childCls, String parentType, String query) {
        return search( mustHasParentQuery(parentType, query), childCls);
    }

    @Override
    public <T extends Entity> Stream<T> searchHasNoParent(Class<T> childCls, String parentType) {
        return search( mustNotHasParentQuery(parentType), childCls);
    }

    @Override
    public <T extends Entity> Stream<T> searchHasNoParent(Class<T> childCls, String parentType, String query) {
        return search( mustNotHasParentQuery(parentType, query), childCls);
    }


    private Stream<Map<String, Object>> search(QueryBuilder query) {
        return resultStream(searchRequest().setQuery( query ));
    }

    private Stream<Map<String, Object>> search(QueryBuilder query, int from, int size) {
        return resultStream(searchRequest(from, size).setQuery( query ));
    }

    private Stream<Map<String, Object>> search(QueryBuilder query, String type) {
        return resultStream(searchRequest().setQuery( query ).setTypes( type ));
    }

    private Stream<Map<String, Object>> search(QueryBuilder query, String type, String... indices) {
        return resultStream(searchRequest().setQuery( query ).setTypes( type ).setIndices( indices ));
    }

    private Stream<Map<String, Object>> search(QueryBuilder query, int from, int to, String type, String... indices) {
        return resultStream(searchRequest(from, to).setQuery( query ).setTypes( type ).setIndices( indices ));
    }

    private <T extends Entity> Stream<T> search(QueryBuilder query, Class<T> cls) {
        final String type = JsonObjectMapper.getType(cls);
        return resultStream(cls, searchRequest().setQuery( query ).setTypes( type ) );
    }

    private <T extends Entity> Stream<T> search(QueryBuilder query, Class<T> cls, String... indices) {
        final String type = JsonObjectMapper.getType(cls);
        return resultStream( cls, searchRequest().setQuery( query ).setTypes( type ).setIndices( indices ));
    }


    /**
     * https://www.elastic.co/guide/en/elasticsearch/client/java-api/current/java-search.html
     */
    private SearchRequestBuilder searchRequest() {
        return searchRequest(DEFAULT_SEARCH_FROM, DEFAULT_SEARCH_SIZE);
    }

    private SearchRequestBuilder searchRequest(int from, int size) {
        return client.prepareSearch()
                .addSort("_doc", SortOrder.ASC)
                .setFrom(from)
                .setSize(size);
    }

    private static <T extends Entity> Stream<T> resultStream(Class<T> cls, SearchRequestBuilder srchReq) {
        return resultStream(cls, searchHitIterable(srchReq));
    }

    private static Iterable<SearchHit> searchHitIterable(SearchRequestBuilder searchRequest) {
        return () -> searchRequest.get().getHits().iterator();
    }

    private static Stream<SearchHit> searchHitStream(Iterable<SearchHit> searchHitIterable) {
        return StreamSupport.stream(searchHitIterable.spliterator(), false);
    }

    private static <T extends Entity> Stream<T> resultStream(Class<T> cls, Iterable<SearchHit> iterable) {
        return searchHitStream(iterable).map( hit -> hitToObject(hit, cls) );
    }

    private static <T extends Entity> T hitToObject(SearchHit searchHit, Class<T> cls) {
        return JsonObjectMapper.getObject(searchHit.getId(), searchHit.getSource(), cls);
    }

    /**
     * Build a search result as JSON Maps from search hit
     * (Add "_index", "_type", "_id" metadata to Map)
     *
     * @param searchHit the search hit to build the Map from
     * @return a Stream of JSONs as {@code Map}s
     */
    private static Map<String, Object> hitToJson(SearchHit searchHit) {
        return new HashMap<String, Object>( searchHit.getSource() ) {{
            put("_index", searchHit.getIndex());
            put("_type",  searchHit.getType());
            put("_id",    searchHit.getId());
        }};
    }

    private static Stream<Map<String, Object>> resultStream(Iterable<SearchHit> searchHitIterable) {
        return searchHitStream(searchHitIterable).map( ElasticsearchIndexer::hitToJson );
    }

    private static Stream<Map<String, Object>> resultStream(SearchRequestBuilder srchReq) {
        return resultStream( searchHitIterable(srchReq) );
    }

    /**
     * https://www.elastic.co/guide/en/elasticsearch/reference/2.3/query-dsl-has-child-query.html
     */
    private static QueryBuilder hasChildQuery(String type, QueryBuilder query) {
        return QueryBuilders.hasChildQuery(type, query, ScoreMode.None);
    }

    private static QueryBuilder mustHasChildQuery(String type) {
        return boolQuery().must( hasChildQuery(type, matchAllQuery()) );
    }

    private static QueryBuilder mustHasChildQuery(String type, String query) {
        return boolQuery().must( hasChildQuery(type,  queryStringQuery(query)) );
    }

    private static QueryBuilder mustNotHasChildQuery(String type) {
        return boolQuery().mustNot( hasChildQuery(type, matchAllQuery()) );
    }

    private static QueryBuilder mustNotHasChildQuery(String type, String query) {
        return boolQuery().mustNot( hasChildQuery(type,  queryStringQuery(query)) );
    }

    /**
     * https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-has-parent-query.html
     */
    private static QueryBuilder hasParentQuery(String type, QueryBuilder query) {
        return QueryBuilders.hasParentQuery(type, query, false);
    }

    private static QueryBuilder mustHasParentQuery(String type) {
        return boolQuery().must( hasParentQuery(type, matchAllQuery()) );
    }

    private static QueryBuilder mustHasParentQuery(String type, String query) {
        return boolQuery().must( hasParentQuery(type,  queryStringQuery(query)) );
    }

    private static QueryBuilder mustNotHasParentQuery(String type) {
        return boolQuery().mustNot( hasParentQuery(type, matchAllQuery()) );
    }

    private static QueryBuilder mustNotHasParentQuery(String type, String query) {
        return boolQuery().mustNot( hasParentQuery(type,  queryStringQuery(query)) );
    }


    /**
     * @return node(s) settings
     */
    private Settings getNodeSettings(NodeType nodeType) {
        if (nodeType.equals(LOCAL)) {
            return Settings.builder()
                    .put("path.home",    HOME)
                    .put("network.host", hosts.get(0))
                    .put("http.host",    hosts.get(0))
                    .put("cluster.name", cluster)
                    .put("node.master",  true)
                    .put("node.data",    true)
                    .put("client.transport.sniff", false)
                    .build();
        } else {
            return Settings.builder()
                    .put("cluster.name", cluster)
                    .put("client.transport.sniff",        true)
                    //.put("client.transport.ping_timeout", timeValueSeconds(DEFAULT_TIMEOUT_INSEC))
                    .build();
        }
    }

    /**
     * @return built index settings
     */
    private Settings getIndexSettings() {
        return Settings.builder()
                .put("index.number_of_shards",   shards)
                .put("index.number_of_replicas", replicas)
                .put("index.max_result_window",  INDEX_MAX_RESULT_WINDOW)
                .build();
    }


    @Override
    protected boolean awaitConnectionIsUp() {
        return ! client
                .admin()
                .cluster()
                .prepareHealth()
                .setTimeout(timeValueSeconds(DEFAULT_TIMEOUT_INSEC))
                .setWaitForStatus(CLUSTER_UP_STATUS.get(nodeType))
                .get()
                .isTimedOut();
    }

    /**
     * Await {@code index} is up and living wrt current node type up status
     */
    @Override
    public boolean awaitIndexIsUp(String index) {
        return awaitIndexIsUp(index, CLUSTER_UP_STATUS.get(nodeType));
    }

    /**
     * Await {@code index} is up and living wrt {@code status} (node type-dependent)
     */
    private boolean awaitIndexIsUp(String index, ClusterHealthStatus status) {
        ClusterHealthResponse resp = client.admin()
                .cluster()
                .prepareHealth(index)
                .setWaitForStatus(status)
                .setTimeout(timeValueSeconds(DEFAULT_TIMEOUT_INSEC))
                .get();
        return ! resp.isTimedOut();
    }

    /**
     * @return new {@link BulkProcessor} instance which flushes when reached
     *   - {@link this#BULKPROCESSOR_FLUSH_ACTIONS} index actions or
     *   - {@link this#BULKPROCESSOR_FLUSH_SIZE} of data or
     *   - {@link this#BULKPROCESSOR_FLUSH_TIME} has passed and
     * and allows {@link this#BULKPROCESSOR_CONCURRENT_REQS} actions to be executed while accumullating bulk requests.
     */
    private BulkProcessor buildBulkProcessor() {
        return BulkProcessor.builder( client,
                new BulkProcessor.Listener() {
                    public void beforeBulk(long executionId, BulkRequest request) {
                        LOGGER.info("INDEXING - BULK PROCESSING of " + String.valueOf(request.numberOfActions()) + " actions");
                    }
                    public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                        if ( ! response.hasFailures())
                            LOGGER.info("INDEXING - BULK PROCESSING TOOK: " + String.valueOf(response.getTook()));
                    }
                    public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                        LOGGER.error("INDEXING - BULK PROCESSOR FAILED: " + String.valueOf(request.toString()), failure);
                    }
                })
                .setBulkActions(BULKPROCESSOR_FLUSH_ACTIONS)
                .setBulkSize(BULKPROCESSOR_FLUSH_SIZE)
                .setFlushInterval(BULKPROCESSOR_FLUSH_TIME)
                .setConcurrentRequests(BULKPROCESSOR_CONCURRENT_REQS)
                .setBackoffPolicy(BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(100), 3))
                .build();
    }

    public static void main(String[] args) {
        final ElasticsearchIndexer elasticsearchIndexer = new ElasticsearchIndexer(new Properties());

    }
}
