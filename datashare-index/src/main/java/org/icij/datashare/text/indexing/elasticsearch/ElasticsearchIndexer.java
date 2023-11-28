package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.core.TimeValue;
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
import org.icij.datashare.text.indexing.ExtractedText;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchedText;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termsQuery;
import static org.icij.datashare.json.JsonObjectMapper.*;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.DEFAULT_SEARCH_SIZE;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSearcher.searchHitStream;


public class ElasticsearchIndexer implements Indexer {
    public final RestHighLevelClient client;
    private final ElasticsearchConfiguration esCfg;

    static private final Map<String, String> memoizeScript = new HashMap<>();

    public static Map<String, String> getMemoizeScript() {
        return memoizeScript;
    }

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
        bulkRequest.add(new UpdateRequest(indexName, parent.getId()).doc(
                jsonBuilder().startObject()
                        .field("status", Document.Status.DONE)
                        .endObject()).routing(routing));
        bulkRequest.add(new UpdateRequest(indexName, parent.getId())
                .script(new Script(ScriptType.INLINE, "painless",
                        "if (!ctx._source.nerTags.contains(params.nerTag)) ctx._source.nerTags.add(params.nerTag);",
                        new HashMap<String, Object>() {{
                            put("nerTag", nerType.toString());
                        }})).routing(routing));

        for (Entity child : namedEntities) {
            bulkRequest.add(createIndexRequest(indexName, JsonObjectMapper.getType(child), child.getId(),
                    getJson(child), parent.getId(), routing));
        }
        bulkRequest.setRefreshPolicy(esCfg.refreshPolicy);

        BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);
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
    public <T extends Entity> boolean bulkAdd(final String indexName, List<T> objs) throws IOException {
        BulkRequest bulkRequest = new BulkRequest();
        objs.stream().map(e -> createIndexRequest(indexName, getType(e), e.getId(), getJson(e), getParent(e), getRoot(e))).forEach(bulkRequest::add);
        return executeBulk(bulkRequest);
    }

    @Override
    public <T extends Entity> boolean bulkUpdate(String indexName, List<T> entities) throws IOException {
        BulkRequest bulkRequest = new BulkRequest();
        entities.stream().map(e -> createUpdateRequest(indexName, getType(e), e.getId(), getJson(e), getParent(e), getRoot(e))).forEach(bulkRequest::add);
        return executeBulk(bulkRequest);
    }

    @Override
    public <T extends Entity> void add(final String indexName, T obj) throws IOException {
        String type = JsonObjectMapper.getType(obj);
        String id = obj.getId();
        client.index(createIndexRequest(indexName, type, id, getJson(obj), getParent(obj), getRoot(obj)).
                setRefreshPolicy(esCfg.refreshPolicy), RequestOptions.DEFAULT);
    }

    @Override
    public <T extends Entity> void update(String indexName, T obj) throws IOException {
        String type = JsonObjectMapper.getType(obj);
        String id = obj.getId();
        client.update(createUpdateRequest(indexName, type, id, getJson(obj), getParent(obj), getRoot(obj)).
                setRefreshPolicy(esCfg.refreshPolicy), RequestOptions.DEFAULT);
    }

    @Override
    public String executeRaw(String method, String url, String rawJson) throws IOException {
        Request request = new Request(method, url.startsWith("/") ? url : "/" + url);
        if (rawJson != null && !rawJson.isEmpty()) {
            request.setJsonEntity(rawJson);
        }
        Response response = client.getLowLevelClient().performRequest(request);
        if ("OPTIONS".equals(method)) {
            return response.getHeader("Allow");
        }
        HttpEntity entity = response.getEntity();
        return entity != null ? EntityUtils.toString(entity) : null;
    }

    private IndexRequest createIndexRequest(String index, String type, String id, Map<String, Object> json, String parent, String root) {
        IndexRequest req = new IndexRequest(index).id(id);

        setJoinFields(json, type, parent);
        req = req.source(json);
        return (parent != null) ? req.routing(root) : req;
    }

    private UpdateRequest createUpdateRequest(String index, String type, String id, Map<String, Object> json, String parent, String root) {
        UpdateRequest req = new UpdateRequest(index, id);

        setJoinFields(json, type, parent);
        req = req.doc(json);
        return (parent != null) ? req.routing(root) : req;
    }

    private void setJoinFields(Map<String, Object> json, String type, String parent) {
        json.put(esCfg.docTypeField, type);
        if (parent != null && type.equals("NamedEntity")) {
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
            final GetRequest req = new GetRequest(indexName, id).routing(root);
            final GetResponse resp = client.get(req, RequestOptions.DEFAULT);
            if (resp.isExists()) {
                Map<String, Object> sourceAsMap = resp.getSourceAsMap();
                sourceAsMap.put("rootDocument", ofNullable(resp.getFields().get("_routing")).orElse(
                        new DocumentField("_routing", Collections.singletonList(id))).getValues().get(0));
                type = (String) sourceAsMap.get(esCfg.docTypeField);
                Class<T> tClass = (Class<T>) Class.forName("org.icij.datashare.text." + type);
                return JsonObjectMapper.getObject(id, resp.getIndex(), sourceAsMap, tClass);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to get entity " + id + " in index " + indexName, e);
        } catch (ClassNotFoundException e) {
            LOGGER.error("no entity for type " + type);
        }
        return null;
    }
    public static String readScriptFile(String painlessFilename) throws IOException {
        InputStream inputStream = ElasticsearchIndexer.class.getClassLoader().getResourceAsStream(painlessFilename);
        return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
    }
    public static String getScriptStringFromFile(String filename) throws IOException {
        String script;
        if (memoizeScript.containsKey(filename)){
            script = memoizeScript.get(filename);
        }else{
            script = ElasticsearchIndexer.readScriptFile(filename);
            memoizeScript.put(filename, script);
        }
        return script;
    }
    private static Script getExtractedTextScript(final int offset, final int limit, final String targetLanguage) throws IOException {
        Map<String,Object> params =  new HashMap<String, Object>() {{
            put("offset", offset);
            put("limit", limit);
        }};
        if(targetLanguage != null){
            params.put("targetLanguage",targetLanguage);
        }
        return new Script(ScriptType.INLINE, "painless",
                ElasticsearchIndexer.getScriptStringFromFile("extractedText.painless.java"),params);
    }


    public ExtractedText getExtractedText(String indexName, String id, String routing, final int offset, final int limit, String targetLanguage) throws IOException {
        String nullRouting = Optional.ofNullable(routing).filter(Predicate.not(String::isBlank)).orElse(id);
        String nullTargetLanguage = Optional.ofNullable(targetLanguage).filter(Predicate.not(String::isBlank)).orElse(null);
        return this.getExtractedContent(indexName, id, nullRouting, offset, limit, nullTargetLanguage);
    }

    private ExtractedText getExtractedContent(String indexName, String id, String routing, final int offset, final int limit, String targetLanguage) throws IOException {
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder().size(DEFAULT_SEARCH_SIZE).timeout(new TimeValue(30, TimeUnit.MINUTES));
        if (offset < 0 || limit < 0) {
            throw new StringIndexOutOfBoundsException(format("offset or limit should not be negative (offset=%d, limit=%d)", offset, limit));
        }
        sourceBuilder.query(boolQuery().must(termsQuery("_id", id)));
        Script script= this.getExtractedTextScript(offset, limit, targetLanguage);
        sourceBuilder.scriptField("pagination", script);
        SearchRequest searchRequest = new SearchRequest(new String[] {indexName}, sourceBuilder);
        SearchResponse search = client.search(searchRequest.routing(routing), RequestOptions.DEFAULT);
        List<SearchHit> tHits = searchHitStream(() -> search.getHits().iterator()).collect(Collectors.toList());
        if(tHits.isEmpty()){
            throw new IllegalArgumentException("Document not found");
        }
        Map<String,Object> pagination = (Map<String, Object>) tHits.get(0).field("pagination").getValues().get(0);
        if(pagination.get("error") != null ){
            int code= ((Integer)pagination.get("code"));
            if (code == 400){
                throw new StringIndexOutOfBoundsException((String)pagination.get("error"));
            }
            else{
                throw new IllegalArgumentException((String)pagination.get("error"));
            }
        }
        ExtractedText extractedText;
        if (targetLanguage != null){
            extractedText = new ExtractedText((String) pagination.get("content"), (Integer) pagination.get("offset"),
                    (Integer) pagination.get("limit"), (Integer) pagination.get("maxOffset"),(String) pagination.get("targetLanguage"));
        } else {
            extractedText =  new ExtractedText((String) pagination.get("content"), (Integer) pagination.get("offset"),
                    (Integer) pagination.get("limit"), (Integer) pagination.get("maxOffset"));
        }
       return extractedText;
    }

    private static Script searchQueryOccurrencesScript(final String query, String targetLanguage) throws IOException {
        Map<String,Object> params = new HashMap<String, Object>() {{
            put("query", query);
        }};
        Script script;
        if(targetLanguage != null){
            params.put("targetLanguage",targetLanguage);
        }
        return new Script(ScriptType.INLINE, "painless",
                ElasticsearchIndexer.getScriptStringFromFile("searchOccurrences.painless.java"),params);
    }
    @Override
    public SearchedText searchTextOccurrences(String indexName, String id, String query, String targetLanguage) throws IOException {
        return this.searchContentOccurrences(indexName, id, id, query, targetLanguage);
    }

    @Override
    public SearchedText searchTextOccurrences(String indexName, String id, String routing, String query, String targetLanguage) throws IOException {
        return this.searchContentOccurrences(indexName, id, routing, query, targetLanguage);

    }
    private SearchedText searchContentOccurrences(String indexName, String id, String routing, final String query, String targetLanguage) throws IOException {
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder().size(DEFAULT_SEARCH_SIZE).timeout(new TimeValue(30, TimeUnit.MINUTES));
        if (query.length() == 0) {
            throw new IllegalArgumentException();
        }
        sourceBuilder.query(boolQuery().must(termsQuery("_id", id)));
        Script script = searchQueryOccurrencesScript(query, targetLanguage);
        sourceBuilder.scriptField("pagination", script);
        SearchRequest searchRequest = new SearchRequest(new String[] {indexName}, sourceBuilder);
        SearchResponse search = client.search(searchRequest.routing(routing), RequestOptions.DEFAULT);
        List<SearchHit> tHits = searchHitStream(() -> search.getHits().iterator()).collect(Collectors.toList());
        if(tHits.isEmpty()){
            throw new IllegalArgumentException("Document not found");
        }
        Map<String,Object> pagination = (Map<String, Object>) tHits.get(0).field("pagination").getValues().get(0);
        if(pagination.get("error") != null ){
            int code= ((Integer)pagination.get("code"));
            if (code == 400){
                throw new StringIndexOutOfBoundsException((String)pagination.get("error"));
            }
            else{
                throw new IllegalArgumentException((String)pagination.get("error"));
            }
        }
        SearchedText searchedText;
        List<Integer> l =(ArrayList<Integer>) pagination.get("offsets");
        int[] offsets = l.stream().mapToInt(i-> i).toArray();
        if (targetLanguage != null){
            searchedText = new SearchedText(
                    offsets,
                    (Integer) pagination.get("count"),
                    (String) pagination.get("query"),
                    (String) pagination.get("targetLanguage"));
        } else {
            searchedText = new SearchedText(
                    offsets,
                    (Integer) pagination.get("count"),
                    (String) pagination.get("query"));
        }
        return searchedText;
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
        UpdateRequest update = new UpdateRequest(prj.getId(), documentId).routing(rootDocument);
        update.script(untagScript);
        update.setRefreshPolicy(esCfg.refreshPolicy);
        UpdateResponse updateResponse = client.update(update, RequestOptions.DEFAULT);
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
        return updateResponse.getBulkFailures().size() == 0 && updateResponse.getUpdated() > 0;
    }

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
                new HashMap<String, Object>() {{
                    put("tags", stream(tags).map(t -> t.label).collect(toList()));
                }});
    }

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
                new HashMap<String, Object>() {{
                    put("tags", stream(tags).map(t -> t.label).collect(toList()));
                }});
    }

    @Override
    public Searcher search(final List<String> indexesNames, Class<? extends Entity> entityClass) {
        return new ElasticsearchSearcher(client, esCfg, indexesNames, entityClass);
    }

    @Override
    public boolean createIndex(final String indexName) {
        return ElasticsearchConfiguration.createIndex(client, indexName);
    }

    @Override
    public boolean deleteAll(String indexName) throws IOException {
        Request post = new Request("POST", indexName + "/_delete_by_query?refresh");
        post.setEntity(new NStringEntity("{\"query\":{\"match_all\": {}}}", ContentType.APPLICATION_JSON));
        Response response = client.getLowLevelClient().performRequest(post);
        return response.getStatusLine().getStatusCode() == RestStatus.OK.getStatus();
    }

    public ElasticsearchIndexer withRefresh(WriteRequest.RefreshPolicy refresh) {
        esCfg.withRefresh(refresh);
        return this;
    }

    private boolean executeBulk(BulkRequest bulkRequest) throws IOException {
        bulkRequest.setRefreshPolicy(esCfg.refreshPolicy);
        BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);
        if (bulkResponse.hasFailures()) {
            for (BulkItemResponse resp : bulkResponse.getItems()) {
                if (resp.isFailed()) {
                    LOGGER.error("bulk request failed : {}", resp.getFailureMessage());
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean getHealth() {
        try {
            return ping();
        } catch (IOException e) {
            LOGGER.error("Index Health Error : ", e);
            return false;
        }
    }

    public boolean ping() throws IOException {
        return client.ping(RequestOptions.DEFAULT);
    }
}
