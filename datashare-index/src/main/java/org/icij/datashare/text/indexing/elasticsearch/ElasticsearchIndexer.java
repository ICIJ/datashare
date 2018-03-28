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
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.join.query.JoinQueryBuilders;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.*;


public class ElasticsearchIndexer implements Indexer {
    private static final int           BULKPROCESSOR_FLUSH_ACTIONS   = 2000;
    private static final ByteSizeValue BULKPROCESSOR_FLUSH_SIZE      = new ByteSizeValue(5, ByteSizeUnit.MB);
    private static final TimeValue     BULKPROCESSOR_FLUSH_TIME      = timeValueSeconds(20);
    private static final int           BULKPROCESSOR_CONCURRENT_REQS = 1;

    private final Client client;
    private final ElasticsearchConfiguration esCfg;

    @Inject
    public ElasticsearchIndexer(final PropertiesProvider propertiesProvider) throws IOException {
        this(createESClient(propertiesProvider), propertiesProvider);
    }

    public ElasticsearchIndexer(final Client client, final PropertiesProvider propertiesProvider) {
        this.client = client;
        esCfg = new ElasticsearchConfiguration(propertiesProvider);
        LOGGER.info("indexer defined with {}", esCfg);
    }

    @Override
    public void close() {
        LOGGER.info("Closing Elasticsearch connection");
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
            final IndexResponse resp = client.index( indexRequest(index, type, id, json, parent).setRefreshPolicy(esCfg.refreshPolicy)).get();
            return asList(RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Failed to add doc " + id + " of type " + type + " in index " + index, e);
            return false;
        }
    }

    @Override
    public boolean bulkAdd(List<NamedEntity> namedEntities, Document parent) throws IOException {
        BulkRequestBuilder bulkRequest = client.prepareBulk();
        Set<Pipeline.Type> nerTags = namedEntities.stream().map(NamedEntity::getExtractor).collect(toSet());
        nerTags.addAll(parent.getNerTags());

        bulkRequest.add(new UpdateRequest(esCfg.indexName, esCfg.indexType, parent.getId()).doc(
                jsonBuilder().startObject()
                        .field("status", Document.Status.DONE)
                        .field("nerTags", nerTags)
                        .endObject()).routing(ofNullable(parent.getParentDocument()).orElse(parent.getId())));
        for (Entity child : namedEntities) {
            bulkRequest.add(indexRequest(esCfg.indexName, JsonObjectMapper.getType(child), child.getId(),
                            JsonObjectMapper.getJson(child), parent.getId()));
        }

        bulkRequest.setRefreshPolicy(esCfg.refreshPolicy);
        BulkResponse bulkResponse = bulkRequest.get();
        if (bulkResponse.hasFailures()) {
            for (BulkItemResponse resp : bulkResponse.getItems()) {
                if (resp.isFailed()) {
                    LOGGER.error("bulk add failed : {}", resp.getFailureMessage());
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public <T extends Entity> boolean add(String index, T obj) {
        return add(index, JsonObjectMapper.getType(obj), obj.getId(), JsonObjectMapper.getJson(obj), JsonObjectMapper.getParent(obj));
    }

    @Override
    public <T extends Entity> boolean add(T obj) {
        return add(esCfg.indexName,
                JsonObjectMapper.getType(obj), obj.getId(),
                JsonObjectMapper.getJson(obj),
                JsonObjectMapper.getParent(obj));
    }

    private IndexRequest indexRequest(String index, String type, String id, Map<String, Object> json) {
        return indexRequest(index, type, id, json, null);
    }

    private IndexRequest indexRequest(String index, String type, String id, Map<String, Object> json, String parent) {
        IndexRequest req = new IndexRequest(index, esCfg.indexType, id);

        json.put(esCfg.docTypeField, type);
        if (parent != null && type.equals("NamedEntity"))
            json.put(esCfg.indexJoinField, new HashMap<String, String>() {{
                put("name", type);
                put("parent", parent);
            }});
        else {
            json.put(esCfg.indexJoinField, new HashMap<String, String>() {{
                put("name", type);
            }});
        }
        req = req.source(json);
        return (parent != null) ? req.routing(parent) : req;
    }


    public <T extends Entity> T get(String id) {
        return get(id, id);
    }

    @Override
    public <T extends Entity> T get(String id, String parent) {
        String type = null;
        try {
            final GetRequest req = new GetRequest(esCfg.indexName, esCfg.indexType, id).routing(parent);
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

    @Override
    public Searcher search(Class<? extends Entity> entityClass) {
        return new ElasticsearchSearcher(client, esCfg, entityClass);
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

    public ElasticsearchIndexer withRefresh(WriteRequest.RefreshPolicy refresh) {
        esCfg.withRefresh(refresh);
        return this;
    }

    static class ElasticsearchSearcher implements Searcher {
        private final SearchRequestBuilder searchBuilder;
        private final BoolQueryBuilder boolQuery;
        private final Class<? extends Entity> cls;

        ElasticsearchSearcher(Client client, ElasticsearchConfiguration config, final Class<? extends Entity> cls) {
            this.cls = cls;
            this.searchBuilder = client.prepareSearch(config.indexName).setTypes(config.indexType);
            this.boolQuery = boolQuery().must(matchQuery("type", JsonObjectMapper.getType(cls)));
        }

        @Override
        public Searcher ofStatus(Document.Status status) {
            this.boolQuery.must(matchQuery("status", status.toString()));
            return this;
        }

        @Override
        public Stream<? extends Entity> execute() {
            SearchResponse response = searchBuilder.setQuery(boolQuery).execute().actionGet();
            return resultStream(this.cls, () -> response.getHits().iterator());
        }

        @Override
        public Searcher withSource(String... fields) {
            searchBuilder.setSource(new SearchSourceBuilder().query(boolQuery).fetchSource(fields, new String[] {}));
            return this;
        }

        @Override
        public Searcher without(Pipeline.Type... nlpPipelines) {
            boolQuery.mustNot(new MatchQueryBuilder("nerTags",
                              Arrays.stream(nlpPipelines).map(Pipeline.Type::toString).collect(toList())));
            return this;
        }

        @Override
        public Searcher with(Pipeline.Type... nlpPipelines) {
            boolQuery.must(new MatchQueryBuilder("nerTags",
                           Arrays.stream(nlpPipelines).map(Pipeline.Type::toString).collect(toList())));
            return this;
        }
    }
}
