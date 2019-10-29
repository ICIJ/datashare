package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.*;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.UpdateByQueryRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.icij.datashare.json.JsonObjectMapper.*;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.DEFAULT_SEARCH_SIZE;


public class ElasticsearchIndexer implements Indexer {
    public final RestHighLevelClient client;
    private final ElasticsearchConfiguration esCfg;

    @Inject
    public ElasticsearchIndexer(final RestHighLevelClient esClient, final PropertiesProvider propertiesProvider) {
        this.client = esClient;
        esCfg = new ElasticsearchConfiguration(propertiesProvider);
        LOGGER.info("indexer defined with {}", esCfg);
    }

    @Override
    public void close() throws IOException {
        LOGGER.info("Closing Elasticsearch connections");
        client.close();
        LOGGER.info("Elasticsearch connections closed");
    }

    @Override
    public boolean bulkAdd(final String indexName, Pipeline.Type nerType, List<NamedEntity> namedEntities, Document parent) throws IOException {
        BulkRequest bulkRequest = new BulkRequest();

        String routing = ofNullable(parent.getRootDocument()).orElse(parent.getId());
        bulkRequest.add(new UpdateRequest(indexName, esCfg.indexType, parent.getId()).doc(
                jsonBuilder().startObject()
                        .field("status", Document.Status.DONE)
                        .endObject()).routing(routing));
        bulkRequest.add(new UpdateRequest(indexName, esCfg.indexType, parent.getId())
                .script(new Script(ScriptType.INLINE, "painless",
                        "if (!ctx._source.nerTags.contains(params.nerTag)) ctx._source.nerTags.add(params.nerTag);",
                        new HashMap<String, Object>() {{put("nerTag", nerType.toString());}})).routing(routing));

        for (Entity child : namedEntities) {
            bulkRequest.add(createIndexRequest(indexName, JsonObjectMapper.getType(child), child.getId(),
                    getJson(child), parent.getId(), routing));
        }
        bulkRequest.setRefreshPolicy(esCfg.refreshPolicy);

        BulkResponse bulkResponse = client.bulk(bulkRequest);
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
    public <T extends Entity> boolean bulkUpdate(String indexName, List<? extends Entity> entities) throws IOException {
        BulkRequest bulkRequest = new BulkRequest();
        entities.stream().map(e -> createUpdateRequest(indexName, getType(e), e.getId(), getJson(e), getParent(e), getRoot(e))).
                forEach(bulkRequest::add);
        bulkRequest.setRefreshPolicy(esCfg.refreshPolicy);

        BulkResponse bulkResponse = client.bulk(bulkRequest);
        if (bulkResponse.hasFailures()) {
            for (BulkItemResponse resp : bulkResponse.getItems()) {
                if (resp.isFailed()) {
                    LOGGER.error("bulk update failed : {}", resp.getFailureMessage());
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public <T extends Entity> void add(final String indexName, T obj) throws IOException {
        String type = JsonObjectMapper.getType(obj);
        String id = obj.getId();
        client.index(createIndexRequest(indexName, type, id, getJson(obj), getParent(obj), getRoot(obj)).
                setRefreshPolicy(esCfg.refreshPolicy));
    }

    @Override
    public <T extends Entity> void update(String indexName, T obj) throws IOException {
        String type = JsonObjectMapper.getType(obj);
        String id = obj.getId();
        client.update(createUpdateRequest(indexName, type, id, getJson(obj), getParent(obj), getRoot(obj)).
                setRefreshPolicy(esCfg.refreshPolicy));
    }

    private IndexRequest createIndexRequest(String index, String type, String id, Map<String, Object> json, String parent, String root) {
        IndexRequest req = new IndexRequest(index, esCfg.indexType, id);

        setJoinFields(json, type, parent, root);
        req = req.source(json);
        return (parent != null) ? req.routing(root) : req;
    }

    private UpdateRequest createUpdateRequest(String index, String type, String id, Map<String, Object> json, String parent, String root) {
        UpdateRequest req = new UpdateRequest(index, esCfg.indexType, id);

        setJoinFields(json, type, parent, root);
        req = req.doc(json);
        return (parent != null) ? req.routing(root) : req;
    }

    private void setJoinFields(Map<String, Object> json, String type, String parent, String root) {
        json.put(esCfg.docTypeField, type);
        if (parent != null && type.equals("NamedEntity")) {
            json.put("rootDocument", root);
            json.put(esCfg.indexJoinField, new HashMap<String, String>() {{
                put("name", type);
                put("parent", parent);
            }});
        } else {
            json.put(esCfg.indexJoinField, new HashMap<String, String>() {{
                put("name", type);
            }});
        }
    }

    public <T extends Entity> T get(String indexName, String id) {
        return get(indexName, id, id);
    }

    @Override
    public <T extends Entity> T get(String indexName, String id, String root) {
        String type = null;
        try {
            final GetRequest req = new GetRequest(indexName, esCfg.indexType, id).routing(root);
            final GetResponse resp = client.get(req);
            if (resp.isExists()) {
                Map<String, Object> sourceAsMap = resp.getSourceAsMap();
                type = (String) sourceAsMap.get(esCfg.docTypeField);
                Class<T> tClass = (Class<T>) Class.forName("org.icij.datashare.text." + type);
                return JsonObjectMapper.getObject(id, sourceAsMap, tClass);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to get entity " + id + " in index " + indexName, e);
        } catch (ClassNotFoundException e) {
            LOGGER.error("no entity for type " + type);
        }
        return null;
    }

    @Override
    public boolean tag(Project prj, String documentId, String rootDocument, Tag... tags) throws IOException {
        return tagUntag(prj, documentId, rootDocument, createTagScript(tags));
    }

    @Override
    public boolean untag(Project prj, String documentId, String rootDocument, Tag... tags) throws IOException {
        return tagUntag(prj, documentId, rootDocument, createUntagScript(tags));
    }

    private boolean tagUntag(Project prj, String documentId, String rootDocument, Script untagScript) throws IOException {
        UpdateRequest update = new UpdateRequest(prj.getId(), esCfg.indexType, documentId).routing(rootDocument);
        update.script(untagScript);
        update.setRefreshPolicy(esCfg.refreshPolicy);
        UpdateResponse updateResponse = client.update(update);
        return updateResponse.status() == RestStatus.OK && updateResponse.getResult() == DocWriteResponse.Result.UPDATED;
    }

    @Override
    public boolean tag(Project prj, List<String> documentIds, Tag... tags) throws IOException {
        return groupTagUntag(prj, documentIds, createTagScript(tags));
    }

    @Override
    public boolean untag(Project prj, List<String> documentIds, Tag... tags) throws IOException {
        return groupTagUntag(prj, documentIds, createUntagScript(tags));
    }

    private boolean groupTagUntag(Project prj, List<String> documentIds, Script untagScript) throws IOException {
        UpdateByQueryRequest updateByQuery = new UpdateByQueryRequest(prj.getId());
        updateByQuery.setQuery(termsQuery("_id", documentIds.toArray(new String[0])));
        updateByQuery.setConflicts("proceed");
        updateByQuery.setScript(untagScript);
        updateByQuery.setRefresh(esCfg.refreshPolicy.getValue().equals("true"));
        BulkByScrollResponse updateResponse = client.updateByQuery(updateByQuery, RequestOptions.DEFAULT);
        return updateResponse.getBulkFailures().size() == 0 && updateResponse.getUpdated() > 0 ;
    }

    @NotNull
    private Script createTagScript(Tag[] tags) {
        return new Script(ScriptType.INLINE, "painless",
                "int updates = 0;" +
                        "if (ctx._source.tags == null) ctx._source.tags = [];" +
                        "for (int i = 0; i < params.tags.length; i++) {" +
                        "  if (!ctx._source.tags.contains(params.tags[i])) {" +
                        "   ctx._source.tags.add(params.tags[i]);" +
                        "   updates++;" +
                        "  }" +
                        "}" +
                        "if (updates == 0) ctx.op = 'noop';",
                new HashMap<String, Object>() {{put("tags", stream(tags).map(t -> t.label).collect(toList()));}});
    }

    @NotNull
    private Script createUntagScript(Tag[] tags) {
        return new Script(ScriptType.INLINE, "painless",
                "int updates = 0;" +
                        "for (int i = 0; i < params.tags.length; i++) {" +
                        "  if (ctx._source.tags.contains(params.tags[i])) {" +
                        "    ctx._source.tags.remove(ctx._source.tags.indexOf(params.tags[i]));" +
                        "    updates++;" +
                        "  }" +
                        "}" +
                        "if (updates == 0) ctx.op = 'noop';",
                new HashMap<String, Object>() {{put("tags", stream(tags).map(t -> t.label).collect(toList()));}});
    }

    @Override
    public Searcher search(final String indexName, Class<? extends Entity> entityClass) {
        return new ElasticsearchSearcher(client, esCfg, indexName, entityClass);
    }

    @Override
    public boolean createIndex(final String indexName) {
        return ElasticsearchConfiguration.createIndex(client, indexName, esCfg.indexType);
    }

    @Override
    public boolean deleteAll(String indexName) throws IOException {
        Response response = client.getLowLevelClient().performRequest("POST", indexName + "/doc/_delete_by_query?refresh",
                new HashMap<>(), new NStringEntity("{\"query\":{\"match_all\": {}}}", ContentType.APPLICATION_JSON));
        return response.getStatusLine().getStatusCode() == RestStatus.OK.getStatus();
    }

    private static Stream<SearchHit> searchHitStream(Iterable<SearchHit> searchHitIterable) {
        return StreamSupport.stream(searchHitIterable.spliterator(), false);
    }

    private static <T extends Entity> Stream<T> resultStream(Class<T> cls, Iterable<SearchHit> iterable) {
        return searchHitStream(iterable).map(hit -> hitToObject(hit, cls));
    }

    private static <T extends Entity> T hitToObject(SearchHit searchHit, Class<T> cls) {
        return JsonObjectMapper.getObject(searchHit.getId(), searchHit.getSourceAsMap(), cls);
    }

    public ElasticsearchIndexer withRefresh(WriteRequest.RefreshPolicy refresh) {
        esCfg.withRefresh(refresh);
        return this;
    }

    static class ElasticsearchSearcher implements Searcher {
        static final TimeValue KEEP_ALIVE = new TimeValue(60000);
        private final BoolQueryBuilder boolQuery;
        private final RestHighLevelClient client;
        private final ElasticsearchConfiguration config;
        private final String indexName;
        private final Class<? extends Entity> cls;
        private final SearchSourceBuilder sourceBuilder;
        private String scrollId;
        private long totalHits;

        ElasticsearchSearcher(RestHighLevelClient client, ElasticsearchConfiguration config, final String indexName, final Class<? extends Entity> cls) {
            this.client = client;
            this.config = config;
            this.indexName = indexName;
            this.cls = cls;
            sourceBuilder = new SearchSourceBuilder().size(DEFAULT_SEARCH_SIZE).timeout(new TimeValue(30, TimeUnit.MINUTES));
            this.boolQuery = boolQuery().must(matchQuery("type", JsonObjectMapper.getType(cls)));
        }

        @Override
        public Searcher ofStatus(Document.Status status) {
            this.boolQuery.must(matchQuery("status", status.toString()));
            return this;
        }

        @Override
        public Stream<? extends Entity> execute() throws IOException {
            sourceBuilder.query(boolQuery);
            SearchRequest searchRequest = new SearchRequest(new String[]{indexName}, sourceBuilder);
            searchRequest.types(config.indexType);
            SearchResponse search = client.search(searchRequest);
            return resultStream(this.cls, () -> search.getHits().iterator());
        }

        @Override
        public Stream<? extends Entity> scroll() throws IOException {
            sourceBuilder.query(boolQuery);
            SearchResponse search;
            if (scrollId == null) {
                SearchRequest searchRequest = new SearchRequest(new String[]{indexName}, sourceBuilder).scroll(KEEP_ALIVE);
                searchRequest.types(config.indexType);
                search = client.search(searchRequest);
                scrollId = search.getScrollId();
                totalHits = search.getHits().totalHits;
            } else {
                search = client.searchScroll(new SearchScrollRequest(scrollId).scroll(KEEP_ALIVE));
                scrollId = search.getScrollId();
            }
            return resultStream(this.cls, () -> search.getHits().iterator());
        }

        @Override
        public Searcher withSource(String... fields) {
            sourceBuilder.fetchSource(fields, new String[]{});
            return this;
        }

        public Searcher withoutSource(String... fields) {
            this.sourceBuilder.fetchSource(new String[] {"*"}, fields);
            return this;
        }

        @Override
        public Searcher withSource(boolean source) {
            sourceBuilder.fetchSource(false);
            return this;
        }

        @Override
        public Searcher without(Pipeline.Type... nlpPipelines) {
            boolQuery.mustNot(new ConstantScoreQueryBuilder(new TermsQueryBuilder("nerTags",
                    stream(nlpPipelines).map(Pipeline.Type::toString).collect(toList()))));
            return this;
        }

        @Override
        public Searcher with(Pipeline.Type... nlpPipelines) {
            boolQuery.must(new ConstantScoreQueryBuilder(new TermsQueryBuilder("nerTags",
                    stream(nlpPipelines).map(Pipeline.Type::toString).collect(toList()))));
            return this;
        }

        @Override
        public Searcher with(Tag... tags) {
            this.boolQuery.must(new ConstantScoreQueryBuilder(new TermsQueryBuilder("tags",
                    stream(tags).map(t -> t.label).collect(toList()))));
            return this;
        }

        @Override
        public Searcher with(String query) {
            return with(query, 0,false);
        }

        @Override
        public Searcher with(String query, int fuzziness, boolean phraseMatches) {
            this.boolQuery.must(new MatchAllQueryBuilder());
            String queryString = query;
            if (phraseMatches) {
                queryString = "\"" + query + "\"" + (fuzziness == 0 ? "": "~" + fuzziness);
            } else if (fuzziness > 0) {
                queryString = Stream.of(query.split(" ")).map(s -> s + "~" + fuzziness).collect(Collectors.joining(" "));
            }
            this.boolQuery.must(new QueryStringQueryBuilder(queryString).defaultField("*"));
            //this.boolQuery.should(new HasChildQueryBuilder("NamedEntity", new MatchQueryBuilder("mention", queryString), ScoreMode.None));
            return this;
        }

        @Override
        public Searcher limit(int maxCount) {
            sourceBuilder.size(maxCount);
            return this;
        }

        @Override
        public Searcher withFieldValues(String key, String... values) {
            if (values.length > 0) this.boolQuery.must(termsQuery(key, values));
            return this;
        }

        @Override
        public Searcher withPrefixQuery(String key, String... values) {
            if (values.length == 0) {
                return this;
            }
            if (values.length == 1) {
                this.boolQuery.must(prefixQuery(key, values[0]));
                return this;
            }
            BoolQueryBuilder innerQuery = new BoolQueryBuilder();
            Arrays.stream(values).forEach(v -> innerQuery.must(prefixQuery(key, v)));
            this.boolQuery.must(innerQuery);
            return this;
        }

        @Override
        public Searcher thatMatchesFieldValue(String name, String value) {
            this.boolQuery.must(matchQuery(name, value));
            return this;
        }

        @Override
        public void clearScroll() throws IOException {
            ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
            clearScrollRequest.addScrollId(scrollId);
            this.client.clearScroll(clearScrollRequest);
            scrollId = null;
            totalHits = 0;
        }

        @Override
        public long totalHits() {
            return totalHits;
        }

        @Override
        public String toString() {
            return "boolQuery : " + boolQuery;
        }
    }
}
