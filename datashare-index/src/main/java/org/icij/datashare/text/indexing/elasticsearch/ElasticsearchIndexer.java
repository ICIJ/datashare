package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.InlineScript;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.ScriptField;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.elasticsearch.core.bulk.UpdateAction;
import co.elastic.clients.elasticsearch.core.bulk.UpdateOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.SourceConfigParam;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.rest.RestStatus;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.indexing.ExtractedText;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.text.indexing.SearchedText;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static co.elastic.clients.elasticsearch.core.UpdateRequest.Builder;
import static java.lang.String.format;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.icij.datashare.json.JsonObjectMapper.getJson;
import static org.icij.datashare.json.JsonObjectMapper.getParent;
import static org.icij.datashare.json.JsonObjectMapper.getRoot;
import static org.icij.datashare.json.JsonObjectMapper.getType;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.DEFAULT_SEARCH_SIZE;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSearcher.searchHitStream;
import static org.icij.datashare.utils.JsonUtils.mapObjectTomapJsonData;

@Singleton
public class ElasticsearchIndexer implements Indexer {
    public final ElasticsearchClient client;
    private final ElasticsearchConfiguration esCfg;

    static private final Map<String, String> memoizeScript = new HashMap<>();

    public static Map<String, String> getMemoizeScript() {
        return memoizeScript;
    }

    @Inject
    public ElasticsearchIndexer(final ElasticsearchClient esClient, final PropertiesProvider propertiesProvider) {
        this.client = esClient;
        esCfg = new ElasticsearchConfiguration(propertiesProvider);
        LOGGER.info("indexer defined with {}", esCfg);
    }

    @Override
    public void close() throws IOException {
        LOGGER.info("Closing Elasticsearch connections");
        client._transport().close();
        LOGGER.info("Elasticsearch connections closed");
    }

    @Override
    public boolean bulkAdd(final String indexName, Pipeline.Type nerType, List<NamedEntity> namedEntities, Document parent) throws IOException {
        BulkRequest.Builder bulkRequest = new BulkRequest.Builder();

        String routing = ofNullable(parent.getRootDocument()).orElse(parent.getId());
        HashMap<String, Object> status = new HashMap<>() {{
            put("status", Document.Status.DONE);
        }};
        bulkRequest.operations(
            BulkOperation.of(op -> op.update(up -> up.index(indexName)
                    .id(parent.getId())
                    .routing(routing)
                    .action(a -> a.doc(status)))),
            BulkOperation.of(op -> op.update(up -> up.index(indexName)
                    .id(parent.getId())
                    .routing(routing)
                    .action(a -> a.script(scr -> scr.inline(iscr -> iscr.lang("painless")
                            .source("if (!ctx._source.nerTags.contains(params.nerTag)) ctx._source.nerTags.add(params.nerTag);")
                            .params("nerTag", JsonData.of(nerType.toString())))))))
        );

        for (Entity child : namedEntities) {
            bulkRequest.operations(op -> op.index(createIndexRequest(indexName, JsonObjectMapper.getType(child), child.getId(),
                    getJson(child), parent.getId(), routing)));
        }

        bulkRequest.refresh(esCfg.refreshPolicy);

        BulkResponse bulkResponse = client.bulk(bulkRequest.build());
        if (bulkResponse.errors()) {
            for (BulkResponseItem resp : bulkResponse.items()) {
                if (resp.error() != null) {
                    LOGGER.error("bulk add failed : {}", resp.error().reason());
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public <T extends Entity> boolean bulkAdd(final String indexName, List<T> objs) throws IOException {
        BulkRequest.Builder bulkRequest = new BulkRequest.Builder();
        for (T obj : objs) {
            bulkRequest.operations(op -> op.index(createIndexRequest(indexName, getType(obj), obj.getId(), getJson(obj), getParent(obj), getRoot(obj))));
        }
        return executeBulk(bulkRequest);
    }

    @Override
    public <T extends Entity> boolean bulkUpdate(String indexName, List<T> entities) throws IOException {
        BulkRequest.Builder bulkRequest = new BulkRequest.Builder();
        for (T e : entities) {
            bulkRequest.operations(op -> op.update(createUpdateRequest(indexName, getType(e), e.getId(), getJson(e), getParent(e), getRoot(e))));
        }
        return executeBulk(bulkRequest);
    }

    @Override
    public <T extends Entity> void add(final String indexName, T obj) throws IOException {
        String type = JsonObjectMapper.getType(obj);
        String id = obj.getId();
        Map<String, Object> json = getJson(obj);
        String parent = getParent(obj);
        String root = getRoot(obj);
        setJoinFields(json, type, parent);
        IndexRequest.Builder<Map<String,Object>> req; req = new IndexRequest.Builder<Map<String,Object>>()
                .index(indexName)
                .id(id)
                .refresh(esCfg.refreshPolicy)
                .document(json);
        if (parent != null) {
            req.routing(root);
        }
        client.index(req.build());
    }

    @Override
    public <T extends Entity> void update(String indexName, T obj) throws IOException {
        String type = JsonObjectMapper.getType(obj);
        String id = obj.getId();
        Map<String, Object> json = getJson(obj);
        String parent = getParent(obj);
        String root = getRoot(obj);
        setJoinFields(json, type, parent);
        UpdateRequest.Builder<Map<String,Object>, Object> req = new UpdateRequest.Builder<Map<String,Object>,Object>()
                .index(indexName)
                .id(id)
                .refresh(esCfg.refreshPolicy)
                .doc(json);
        if (parent != null) {
            req.routing(root);
        }
        client.update(req.build(), Object.class);
    }

    @Override
    public boolean exists(String indexName) throws IOException {
        co.elastic.clients.elasticsearch.indices.ExistsRequest request =
                new co.elastic.clients.elasticsearch.indices.ExistsRequest.Builder()
                        .index(indexName)
                        .build();
        return client.indices().exists(request).value();
    }

    @Override
    public boolean exists(String indexName, String id) throws IOException {
        ExistsRequest.Builder getRequest = new ExistsRequest.Builder().index(indexName).id(id);
        getRequest.source(SourceConfigParam.of(scp -> scp.fetch(false)));
        getRequest.storedFields("_none_");
        return client.exists(getRequest.build()).value();
    }

    @Override
    public String executeRaw(String method, String url, String rawJson) throws IOException {
        Request request = new Request(method, url.startsWith("/") ? url : "/" + url);
        if (rawJson != null && !rawJson.isEmpty()) {
            request.setJsonEntity(rawJson);
        }
        RestClient restClient = ((RestClientTransport) client._transport()).restClient();
        Response response = restClient.performRequest(request);
        if ("OPTIONS".equals(method)) {
            return response.getHeader("Allow");
        }
        HttpEntity entity = response.getEntity();
        return entity != null ? EntityUtils.toString(entity) : null;
    }

    private IndexOperation<Map<String, Object>> createIndexRequest(String index, String type, String id, Map<String, Object> json, String parent, String root) {
        IndexOperation.Builder<Map<String, Object>> req = new IndexOperation.Builder<>();
        req.index(index).id(id);

        setJoinFields(json, type, parent);
        req.document(json);
        if (parent != null) {
            req.routing(root);
        }
        return req.build();
    }

    private UpdateOperation<Object, Object> createUpdateRequest(String index, String type, String id, Map<String, Object> json, String parent, String root) {
        UpdateOperation.Builder<Object, Object> req = new UpdateOperation.Builder<>();
        req.index(index).id(id);

        setJoinFields(json, type, parent);
        req.action(UpdateAction.of(a -> a.doc(json)));
        if (parent != null) {
            req.routing(root);
        }
        return req.build();
    }

    private void setJoinFields(Map<String, Object> json, String type, String parent) {
        json.put(esCfg.docTypeField, type);
        if (parent != null && (type.equals("NamedEntity") || type.equals("Duplicate"))) {
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
    public <T extends Entity> T get(String indexName, String id, List<String> sourceExcludes) {
        return get(indexName, id, id, sourceExcludes);
    }

    @Override
    public <T extends Entity> T get(String indexName, String id, String root) {
        return get(indexName, id, root, List.of());
    }

    @Override
    public <T extends Entity> T get(String indexName, String id, String root, List<String> sourceExcludes) {
        String type = null;
        try {
            final GetRequest req = new GetRequest.Builder()
                    .index(indexName)
                    .id(id)
                    .routing(root)
                    .sourceExcludes(sourceExcludes)
                    .build();
            GetResponse<ObjectNode> resp = client.get(req, ObjectNode.class);
            if (resp.found()) {
                Map<String, Object> sourceAsMap = MAPPER.readValue(MAPPER.writeValueAsString(resp.source()), new TypeReference<>() {});
                sourceAsMap.put("rootDocument", ofNullable(resp.routing()).orElse(id));
                type = (String) sourceAsMap.get(esCfg.docTypeField);
                Class<T> tClass = (Class<T>) Class.forName("org.icij.datashare.text." + type);
                return JsonObjectMapper.getObject(id, resp.index(), sourceAsMap, tClass);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to get entity " + id + " in index " + indexName, e);
        } catch (ClassNotFoundException e) {
            LOGGER.error("No entity for type " + type);
        }
        return null;
    }
    public static String readScriptFile(String painlessFilename) throws IOException {
        InputStream inputStream = ElasticsearchIndexer.class.getClassLoader().getResourceAsStream(painlessFilename);
        if (inputStream != null) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        } else {
            throw new FileNotFoundException(String.format("Unable to find : %s", painlessFilename));
        }
    }
    public static String getScriptStringFromFile(String filename) throws IOException {
        String script;
        if (memoizeScript.containsKey(filename)) {
            script = memoizeScript.get(filename);
        } else {
            script = ElasticsearchIndexer.readScriptFile(filename);
            memoizeScript.put(filename, script);
        }
        return script;
    }
    private static InlineScript getExtractedTextScript(final int offset, final int limit, final String targetLanguage) throws IOException {
        Map<String,Object> params =  new HashMap<String, Object>() {{
            put("offset", offset);
            put("limit", limit);
        }};
        if(targetLanguage != null){
            params.put("targetLanguage",targetLanguage);
        }
        return new InlineScript.Builder().lang("painless")
                .source(ElasticsearchIndexer.getScriptStringFromFile("extractedText.painless.java"))
                .params(mapObjectTomapJsonData(params)).build();
    }

    public ExtractedText getExtractedText(String indexName, String id, String routing, final int offset, final int limit, String targetLanguage) throws IOException {
        String nullRouting = Optional.ofNullable(routing).filter(Predicate.not(String::isBlank)).orElse(id);
        String nullTargetLanguage = Optional.ofNullable(targetLanguage).filter(Predicate.not(String::isBlank)).orElse(null);
        return this.getExtractedContent(indexName, id, nullRouting, offset, limit, nullTargetLanguage);
    }

    private ExtractedText getExtractedContent(String indexName, String id, String routing, final int offset, final int limit, String targetLanguage) throws IOException {
        SearchRequest.Builder sourceBuilder = new SearchRequest.Builder().index(indexName).size(DEFAULT_SEARCH_SIZE).timeout("30m");
        if (offset < 0 || limit < 0) {
            throw new StringIndexOutOfBoundsException(format("offset or limit should not be negative (offset=%d, limit=%d)", offset, limit));
        }
        sourceBuilder.query(Query.of(q -> q.bool(bq -> bq.must(qt -> qt.term(t -> t.field("_id").value(id))))));
        InlineScript script = getExtractedTextScript(offset, limit, targetLanguage);
        sourceBuilder.scriptFields("pagination", ScriptField.of(sf -> sf.script(scr -> scr.inline(script))));
        SearchResponse<ObjectNode> search = client.search(sourceBuilder.routing(routing).build(), ObjectNode.class);
        List<Hit<ObjectNode>> tHits = searchHitStream(() -> search.hits().hits().iterator()).collect(toList());
        if(tHits.isEmpty()){
            throw new IllegalArgumentException("Document not found");
        }
        ArrayList<Map<String,Object>> tHitsPaginationArray = tHits.get(0).fields().get("pagination")
                .to(MAPPER.getTypeFactory().constructCollectionType(ArrayList.class, Map.class));
        Map<String,Object> pagination = tHitsPaginationArray.get(0);
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

    private static InlineScript searchQueryOccurrencesScript(final String query, String targetLanguage) throws IOException {
        Map<String,Object> params = new HashMap<String, Object>() {{
            put("query", query);
        }};

        if(targetLanguage != null){
            params.put("targetLanguage",targetLanguage);
        }

        return new InlineScript.Builder().lang("painless")
                .source(ElasticsearchIndexer.getScriptStringFromFile("searchOccurrences.painless.java"))
                .params(mapObjectTomapJsonData(params)).build();
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
        SearchRequest.Builder sourceBuilder = new SearchRequest.Builder().index(indexName).size(DEFAULT_SEARCH_SIZE).timeout("30m");
        if (query.isEmpty()) {
            throw new IllegalArgumentException();
        }
        sourceBuilder.query(Query.of(q -> q.bool(bq -> bq.must(qt -> qt.term(t -> t.field("_id").value(id))))));
        InlineScript script = searchQueryOccurrencesScript(query, targetLanguage);
        sourceBuilder.scriptFields("pagination", ScriptField.of(sf -> sf.script(scr -> scr.inline(script))));
        SearchResponse<ObjectNode> search = client.search(sourceBuilder.routing(routing).build(), ObjectNode.class);
        List<Hit<ObjectNode>> tHits = searchHitStream(() -> search.hits().hits().iterator()).collect(toList());
        if(tHits.isEmpty()){
            throw new IllegalArgumentException("Document not found");
        }
        ArrayList<Map<String,Object>> tHitsPaginationArray = tHits.get(0).fields().get("pagination")
                .to(MAPPER.getTypeFactory().constructCollectionType(ArrayList.class, Map.class));
        Map<String,Object> pagination = tHitsPaginationArray.get(0);
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
        List<Integer> l = MAPPER.convertValue(pagination.get("offsets"), new TypeReference<>() {});
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

    private boolean tagUntag(Project prj, String documentId, String rootDocument, InlineScript untagScript) throws IOException {
        Builder<Object, Object> updateRequest = new Builder<>().index(prj.getId()).id(documentId).routing(rootDocument);
        updateRequest.script(co.elastic.clients.elasticsearch._types.Script.of(scr -> scr.inline(untagScript)));
        updateRequest.refresh(esCfg.refreshPolicy);
        UpdateResponse<Object> updateResponse = client.update(updateRequest.build(), ObjectNode.class);
        return updateResponse.result() == Result.Updated;
    }

    @Override
    public boolean tag(Project prj, List<String> documentIds, Tag... tags) throws IOException {
        return groupTagUntag(prj, documentIds, createTagScript(tags));
    }

    @Override
    public boolean untag(Project prj, List<String> documentIds, Tag... tags) throws IOException {
        return groupTagUntag(prj, documentIds, createUntagScript(tags));
    }

    private boolean groupTagUntag(Project prj, List<String> documentIds, InlineScript untagScript) throws IOException {
        UpdateByQueryRequest.Builder updateByQuery = new UpdateByQueryRequest.Builder().index(prj.getId());
        updateByQuery.query(q -> q.terms(qt -> qt.field("_id")
                                                 .terms(tq -> tq.value(stream(documentIds.toArray(new String[0])).map(FieldValue::of).collect(toList())))));
        updateByQuery.conflicts(Conflicts.Proceed);
        updateByQuery.script(scr -> scr.inline(untagScript));
        updateByQuery.refresh(esCfg.refreshPolicy.equals(Refresh.True));
        UpdateByQueryResponse updateResponse = client.updateByQuery(updateByQuery.build());
        int updated = updateResponse.updated() != null ? updateResponse.updated().intValue() : 0;
        return updateResponse.failures().size() == 0 && updated > 0;
    }

    private InlineScript createTagScript(Tag[] tags) {
        return new InlineScript.Builder().lang("painless")
                .source(                "int updates = 0;" +
                        "if (ctx._source.tags == null) ctx._source.tags = [];" +
                        "for (int i = 0; i < params.tags.length; i++) {" +
                        "  if (!ctx._source.tags.contains(params.tags[i])) {" +
                        "   ctx._source.tags.add(params.tags[i]);" +
                        "   updates++;" +
                        "  }" +
                        "}" +
                        "if (updates == 0) ctx.op = 'noop';")
                .params(mapObjectTomapJsonData(new HashMap<String, Object>() {{
                    put("tags", stream(tags).map(t -> t.label).collect(toList()));
                }})).build();
    }

    private InlineScript createUntagScript(Tag[] tags) {
        return new InlineScript.Builder().lang("painless")
                .source("int updates = 0;" +
                        "for (int i = 0; i < params.tags.length; i++) {" +
                        "  if (ctx._source.tags.contains(params.tags[i])) {" +
                        "    ctx._source.tags.remove(ctx._source.tags.indexOf(params.tags[i]));" +
                        "    updates++;" +
                        "  }" +
                        "}" +
                        "if (updates == 0) ctx.op = 'noop';")
                .params(mapObjectTomapJsonData(new HashMap<String, Object>() {{
                    put("tags", stream(tags).map(t -> t.label).collect(toList()));
                }})).build();
    }

    @Override
    public QueryBuilderSearcher search(List<String> indexesNames, Class<? extends Entity> entityClass) {
        return new ElasticsearchQueryBuilderSearcher(client, indexesNames, entityClass);
    }

    @Override
    public Searcher search(final List<String> indexesNames, Class<? extends Entity> entityClass, SearchQuery query) {
        return query.isJsonQuery() ?
                new ElasticsearchSearcher(client, indexesNames, entityClass, query.asJson()):
                new ElasticsearchQueryBuilderSearcher(client, indexesNames, entityClass, query);
    }

    @Override
    public boolean createIndex(final String indexName) {
        return ElasticsearchConfiguration.createIndex(client, indexName);
    }

    @Override
    public boolean deleteAll(String indexName) throws IOException {
        if (!exists(indexName)) return false;
        Request post = new Request("POST", indexName + "/_delete_by_query?refresh");
        post.setEntity(new NStringEntity("{\"query\":{\"match_all\": {}}}", ContentType.APPLICATION_JSON));
        RestClient restClient = ((RestClientTransport) client._transport()).restClient();
        Response response = restClient.performRequest(post);
        return response.getStatusLine().getStatusCode() == RestStatus.OK.getStatus();
    }

    public ElasticsearchIndexer withRefresh(Refresh refresh) {
        esCfg.withRefresh(refresh);
        return this;
    }

    private boolean executeBulk(BulkRequest.Builder bulkRequest) throws IOException {
      bulkRequest.refresh(esCfg.refreshPolicy);
      BulkResponse bulkResponse = client.bulk(bulkRequest.build());
      if (bulkResponse.errors()) {
          for (BulkResponseItem resp : bulkResponse.items()) {
              if (resp.error() != null) {
                  LOGGER.error("bulk request failed : {}", resp.error().reason());
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
        return client.ping().value();
    }
}
