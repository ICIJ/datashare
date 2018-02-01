package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
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
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.join.query.JoinQueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortOrder;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.indexing.Indexer;

import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.*;


public class ElasticsearchIndexer implements Indexer {
    private static final int           BULKPROCESSOR_FLUSH_ACTIONS   = 2000;
    private static final ByteSizeValue BULKPROCESSOR_FLUSH_SIZE      = new ByteSizeValue(5, ByteSizeUnit.MB);
    private static final TimeValue     BULKPROCESSOR_FLUSH_TIME      = timeValueSeconds(20);
    private static final int           BULKPROCESSOR_CONCURRENT_REQS = 1;

    private final Client client;
    private final BulkProcessor bulkProcessor;
    private final ElasticsearchConfiguration esCfg;

    @Inject
    public ElasticsearchIndexer(final PropertiesProvider propertiesProvider) throws UnknownHostException {
        this(createESClient(propertiesProvider), propertiesProvider);
    }

    public ElasticsearchIndexer(final Client client, final PropertiesProvider propertiesProvider) {
        this.client = client;
        esCfg = new ElasticsearchConfiguration(propertiesProvider);
        LOGGER.info("indexer defined with {}", esCfg);
        bulkProcessor = buildBulkProcessor();
    }

    @Override
    public void close() {
        LOGGER.info("Closing Elasticsearch connection");
        try {
            LOGGER.info("Awaiting bulk processor termination...");
            bulkProcessor.awaitClose(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            LOGGER.error("Failed to close bulk processor", e);
        }
        client.close();
        LOGGER.info("Elasticsearch connection closed");
    }

    // __________ Admin indices __________
    @Override
    public boolean createIndex(String index) {
        try {
            final CreateIndexRequest  req  = new CreateIndexRequest(index).settings(esCfg.getIndexSettings());
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
    public boolean add(String index, String type, String id, Map<String, Object> json) {
        try {
            final IndexResponse resp = client.index( indexRequest(index, type, id, json) ).get();
            return asList(RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to add doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public boolean add(String index, String type, String id, Map<String, Object> json, String parent) {
        try {
            final IndexResponse resp = client.index( indexRequest(index, type, id, json, parent) ).get();
            return asList(RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to add doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public <T extends Entity> boolean add(String index, T obj) {
        return add(index, JsonObjectMapper.getType(obj), obj.getId(), JsonObjectMapper.getJson(obj), JsonObjectMapper.getParent(obj).orElse(null));
    }

    private IndexRequest indexRequest(String index, String type, String id, Map<String, Object> json) {
        return indexRequest(index, type, id, json, null);
    }

    private IndexRequest indexRequest(String index, String type, String id, Map<String, Object> json, String parent) {
        IndexRequest req = new IndexRequest(index, esCfg.indexType, id);

        json.put(esCfg.docTypeField, type);
        if(esCfg.PARENT_TYPES.contains(type))
            json.put(esCfg.indexJoinField, type);
        if (parent != null)
            json.put(esCfg.indexJoinField, new HashMap<String, String>() {{
                put("name", type);
                put("parent", parent);
            }});
        req = req.source(json);

        return (parent != null) ? req.routing(parent) : req;
    }


    // __________ Batched Add Document(s) __________

    @Override
    public void addBatch(String index, String type, String id, Map<String, Object> json) {
        bulkProcessor.add( indexRequest(index, type, id, json) );
    }

    @Override
    public void addBatch(String index, String type, String id, Map<String, Object> json, String parent) {
        bulkProcessor.add( indexRequest(index, type, id, json, parent) );
    }

    // __________ Read Document __________

    public <T extends Entity> T get(String id) {
        String type = null;
        try {
            final GetRequest  req  = new GetRequest(esCfg.indexName, esCfg.indexType, id);
            final GetResponse resp = client.get(req).get();
            if (resp.isExists()) {
                Map<String, Object> sourceAsMap = resp.getSourceAsMap();
                type = (String) sourceAsMap.get(esCfg.docTypeField);
                Class<T> tClass = (Class<T>) Class.forName("org.icij.datashare.text." + type);
                return JsonObjectMapper.getObject(id, sourceAsMap, tClass);
            }
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to get entity " + id + " in index " + esCfg.indexName, e);
        } catch (ClassNotFoundException e) {
            LOGGER.error("no entity for type " + type);
        }
        return null;
    }

    // __________ Delete Document __________
    @Override
    public boolean delete(String index, String type, String id) {
        try {
            final DeleteResponse resp = client.delete( deleteRequest(index, type, id) ).get();
            return asList(RestStatus.FOUND, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to delete doc " + id  + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public boolean delete(String index, String type, String id, String parent) {
        try {
            final DeleteResponse resp = client.delete( deleteRequest(index, type, id, parent) ).get();
            return asList(RestStatus.FOUND, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to delete doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    private DeleteRequest deleteRequest(String index, String type, String id) {
        return deleteRequest(index, type, id, null);
    }

    private DeleteRequest deleteRequest(String index, String type, String id, String parent) {
        DeleteRequest req = new DeleteRequest(index, esCfg.indexType, id);
        return (parent != null) ? req.routing(parent) : req;
    }

        // __________ Batched Delete Document(s) __________

    @Override
    public void batchDelete(String index, String type, String id) {
        bulkProcessor.add( deleteRequest(index, type, id) );
    }

    @Override
    public void batchDelete(String index, String type, String id, String parent) {
        bulkProcessor.add( deleteRequest(index, type, id, parent) );
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
    public Stream<Map<String, Object>> search(String query, String type, String... indices) {
        return search( queryStringQuery(query), type, indices);
    }

    @Override
    public Stream<Map<String, Object>> search(String query, int from, int to, String type, String... indices) {
        return search( queryStringQuery(query), from, to, type, indices);
    }

    // __________ Search Document(s) with(out) children __________

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
        return JsonObjectMapper.getObject(searchHit.getId(), searchHit.getSourceAsMap(), cls);
    }

    /**
     * Build a search result as JSON Maps from search hit
     * (Add "_index", "_type", "_id" metadata to Map)
     *
     * @param searchHit the search hit to build the Map from
     * @return a Stream of JSONs as {@code Map}s
     */
    private static Map<String, Object> hitToJson(SearchHit searchHit) {
        return new HashMap<String, Object>( searchHit.getSourceAsMap() ) {{
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
        return JoinQueryBuilders.hasChildQuery(type, query, ScoreMode.None);
    }

    private static QueryBuilder mustHasChildQuery(String type) {
        return boolQuery().must( hasChildQuery(type, matchAllQuery()) );
    }

    private static QueryBuilder mustHasChildQuery(String type, String query) {
        return boolQuery().must( hasChildQuery(type,  queryStringQuery(query)) );
    }

    /**
     * https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-has-parent-query.html
     */
    private static QueryBuilder hasParentQuery(String type, QueryBuilder query) {
        return JoinQueryBuilders.hasParentQuery(type, query, false);
    }

    private static QueryBuilder mustHasParentQuery(String type) {
        return boolQuery().must( hasParentQuery(type, matchAllQuery()) );
    }

    private static QueryBuilder mustHasParentQuery(String type, String query) {
        return boolQuery().must( hasParentQuery(type,  queryStringQuery(query)) );
    }

    private boolean awaitConnectionIsUp() {
        return ! client
                .admin()
                .cluster()
                .prepareHealth()
                .setTimeout(timeValueSeconds(DEFAULT_TIMEOUT_INSEC))
                .setWaitForStatus(ClusterHealthStatus.GREEN)
                .get()
                .isTimedOut();
    }

    @Override
    public boolean awaitIndexIsUp(String index) {
        return awaitIndexIsUp(index, ClusterHealthStatus.GREEN);
    }

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
                        LOGGER.info("Indexing - bulk processing of " + String.valueOf(request.numberOfActions()) + " actions");
                    }
                    public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                        if ( ! response.hasFailures())
                            LOGGER.info("Indexing - bulk processing took: " + String.valueOf(response.getTook()));
                    }
                    public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                        LOGGER.error("Indexing - bulk processor failed: " + String.valueOf(request.toString()), failure);
                    }
                })
                .setBulkActions(BULKPROCESSOR_FLUSH_ACTIONS)
                .setBulkSize(BULKPROCESSOR_FLUSH_SIZE)
                .setFlushInterval(BULKPROCESSOR_FLUSH_TIME)
                .setConcurrentRequests(BULKPROCESSOR_CONCURRENT_REQS)
                .setBackoffPolicy(BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(100), 3))
                .build();
    }
}
