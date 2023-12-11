package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.ClearScrollRequest;
import co.elastic.clients.elasticsearch.core.ScrollRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.ResponseBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.icij.datashare.Entity;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.utils.JsonUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static co.elastic.clients.elasticsearch.core.SearchRequest.Builder;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.DEFAULT_SEARCH_SIZE;

class ElasticsearchSearcher implements Indexer.Searcher {
    static final Time KEEP_ALIVE = Time.of(t -> t.time("60000ms"));
    private final BoolQuery.Builder boolQueryBuilder;
    private final ElasticsearchClient client;
    private final List<String> indexesNames;
    private final Class<? extends Entity> cls;
    private final Builder sourceBuilder;
    private String scrollId;
    private SearchRequest scrollSearchRequest;
    private long totalHits;
    private final static String TEMPLATE_QUERY = "<query>";

    ElasticsearchSearcher(ElasticsearchClient client, ElasticsearchConfiguration config, final List<String> indexesNames, final Class<? extends Entity> cls) {
        this.client = client;
        this.indexesNames = indexesNames;
        this.cls = cls;
        sourceBuilder = new Builder().size(DEFAULT_SEARCH_SIZE).timeout("30m");
        this.boolQueryBuilder = new BoolQuery.Builder().must(must -> must.match(m -> m.field("type").query(JsonObjectMapper.getType(cls))));
    }

    static Stream<Hit<ObjectNode>> searchHitStream(Iterable<Hit<ObjectNode>> searchHitIterable) {
        return StreamSupport.stream(searchHitIterable.spliterator(), false);
    }

    static <T extends Entity> Stream<T> resultStream(Class<T> cls, Iterable<Hit<ObjectNode>> iterable) {
        return searchHitStream(iterable).map(hit -> hitToObject(hit, cls));
    }

    static <T extends Entity> T hitToObject(Hit<ObjectNode> searchHit, Class<T> cls) {
        return (T) JsonObjectMapper.getObject(searchHit.id(), searchHit.index(), JsonUtils.nodeToMap(searchHit.source()), cls);
    }
    
    @Override
    public Indexer.Searcher ofStatus(Document.Status status) {
        this.boolQueryBuilder.must(must -> must.match(mq -> mq.field("status").query(status.toString())));
        return this;
    }

    @Override
    public Stream<? extends Entity> execute() throws IOException {
        BoolQuery boolQueryToBuild = boolQueryBuilder.build();
        sourceBuilder.index(indexesNames).query(q -> q.bool(boolQueryToBuild));
        SearchRequest searchRequest = sourceBuilder.build();
        SearchResponse<ObjectNode> search = client.search(searchRequest, ObjectNode.class);
        return resultStream(this.cls, () -> search.hits().hits().iterator());
    }

    @Override
    public Stream<? extends Entity> scroll() throws IOException {
        return scroll(0, 0);
    }

    @Override
    public Stream<? extends Entity> scroll(int numSlice, int nbSlices) throws IOException {
        ResponseBody<ObjectNode> response;
        if (scrollSearchRequest == null) {
            sourceBuilder.index(indexesNames).query(q -> q.bool(boolQueryBuilder.build()));
            if (nbSlices > 1) {
                sourceBuilder.slice(s -> s.id(String.valueOf(numSlice)).max(nbSlices));
            }
            scrollSearchRequest = sourceBuilder.scroll(KEEP_ALIVE).build();
            response = client.search(scrollSearchRequest, ObjectNode.class);
            totalHits = response.hits().total().value();
        } else {
            response = client.scroll(ScrollRequest.of(s -> s.scroll(KEEP_ALIVE)
                            .scrollId(ofNullable(scrollId)
                                    .orElseThrow(() -> new IllegalStateException("ScrollId must have been cleared")))), ObjectNode.class);
        }
        scrollId = response.scrollId();
        return resultStream(this.cls, () -> response.hits().hits().iterator());
    }

    @Override
    public Indexer.Searcher withSource(String... fields) {
        sourceBuilder.source(s -> s.filter(f -> f.includes(stream(fields).collect(Collectors.toList()))));
        return this;
    }

    public Indexer.Searcher withoutSource(String... fields) {
        this.sourceBuilder.source(s -> s.filter(f -> f.excludes(stream(fields).collect(Collectors.toList()))));
        return this;
    }

    @Override
    public Indexer.Searcher withSource(boolean source) {
        sourceBuilder.source(s -> s.fetch(source));
        return this;
    }

    @Override
    public Indexer.Searcher without(Pipeline.Type... nlpPipelines) {
        this.boolQueryBuilder.mustNot(q -> q.constantScore(cs -> cs.filter(qt -> qt.terms(t -> t.field("nerTags").terms(tqf -> tqf.value(
                stream(nlpPipelines).map(Pipeline.Type::toString).map(FieldValue::of).collect(toList()))
        )))));
        return this;
    }

    @Override
    public Indexer.Searcher with(Pipeline.Type... nlpPipelines) {
        this.boolQueryBuilder.must(q -> q.constantScore(cs -> cs.filter(qt -> qt.terms(t -> t.field("nerTags").terms(tqf -> tqf.value(
                stream(nlpPipelines).map(Pipeline.Type::toString).map(FieldValue::of).collect(toList()))
        )))));
        return this;
    }

    @Override
    public Indexer.Searcher with(Tag... tags) {
        this.boolQueryBuilder.must(q -> q.constantScore(cs -> cs.filter(qt -> qt.terms(t -> t.field("tags").terms(tqf -> tqf.value(
                stream(tags).map(tag -> FieldValue.of(tag.label)).collect(toList()))
        )))));
        return this;
    }

    @Override
    public Indexer.Searcher with(String query) {
        return with(query, 0, false);
    }

    @Override
    public Indexer.Searcher set(JsonNode jsonQuery) {
        this.boolQueryBuilder.must(m -> m.withJson(new StringReader(jsonQuery.toString())));
        return this;
    }

    @Override
    public Indexer.Searcher setFromTemplate(String jsonQueryTemplate, String query, int fuzziness, boolean phraseMatches) {
        String queryString = buildQueryString(query, fuzziness, phraseMatches, "\\\\\"");
        final String queryBody = jsonQueryTemplate.replaceAll(TEMPLATE_QUERY,queryString);
        this.boolQueryBuilder.must(m -> m.withJson(new StringReader(queryBody)));
        return this;
    }

    @Override
    public Indexer.Searcher with(String query, int fuzziness, boolean phraseMatches) {
        String queryString = buildQueryString(query, fuzziness, phraseMatches, "\"");
        this.boolQueryBuilder.must(m -> m.matchAll(ma -> ma));
        this.boolQueryBuilder.must(m -> m.queryString(qs -> qs.query(queryString)));
        return this;
    }
    private static String buildQueryString(String query, int fuzziness, boolean phraseMatches, String phraseMatchDoubleQuotes) {
        String queryString;
        if (phraseMatches) {
            queryString = phraseMatchDoubleQuotes + query + phraseMatchDoubleQuotes + (fuzziness == 0 ? "" : "~" + fuzziness);
        } else if (fuzziness > 0) {
            queryString = Stream.of(query.split(" ")).map(s -> s + "~" + fuzziness).collect(Collectors.joining(" "));
        } else {
            queryString = query;
        }
        return queryString;
    }

    @Override
    public Indexer.Searcher limit(int maxCount) {
        sourceBuilder.size(maxCount);
        return this;
    }

    @Override
    public Indexer.Searcher withFieldValues(String key, String... values) {
        if (values.length > 0) {
            this.boolQueryBuilder.must(m -> m.terms(t -> t.field(key).terms(tqf -> tqf.value(stream(values).map(FieldValue::of).collect(toList())))));
        }
        return this;
    }

    @Override
    public Indexer.Searcher withPrefixQuery(String key, String... values) {
        if (values.length == 0) {
            return this;
        }
        if (values.length == 1) {
            this.boolQueryBuilder.must(m -> m.prefix(p -> p.field(key).value(values[0])));
            return this;
        }
        BoolQuery.Builder innerQuery = new BoolQuery.Builder();
        Arrays.stream(values).forEach(v -> innerQuery.must(m -> m.prefix(p -> p.field(key).value(v))));
        this.boolQueryBuilder.must(m -> m.bool(innerQuery.build()));
        return this;
    }

    @Override
    public Indexer.Searcher thatMatchesFieldValue(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("[ match ] requires query value");
        }
        this.boolQueryBuilder.must(m -> m.match(q -> q.field(name).query(FieldValue.of(value.toString()))));
        return this;
    }

    @Override
    public void clearScroll() throws IOException {
        this.client.clearScroll(ClearScrollRequest.of(csr -> csr.scrollId(scrollId)));
        scrollId = null;
        totalHits = 0;
    }

    @Override
    public long totalHits() {
        return totalHits;
    }

    @Override
    public String toString() {
        return "boolQuery : " + boolQueryBuilder;
    }
}