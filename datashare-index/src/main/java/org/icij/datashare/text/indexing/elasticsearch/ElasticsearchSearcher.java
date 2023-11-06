package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
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

import static co.elastic.clients.elasticsearch.core.SearchRequest.*;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.DEFAULT_SEARCH_SIZE;

class ElasticsearchSearcher implements Indexer.Searcher {
    static final Time KEEP_ALIVE = Time.of(t -> t.time("60000ms"));
    private BoolQuery.Builder boolQuery;
    private final ElasticsearchClient client;
    private final List<String> indexesNames;
    private final Class<? extends Entity> cls;
    private Builder sourceBuilder;
    private String scrollId;
    private long totalHits;

    ElasticsearchSearcher(ElasticsearchClient client, ElasticsearchConfiguration config, final List<String> indexesNames, final Class<? extends Entity> cls) {
        this.client = client;
        this.indexesNames = indexesNames;
        this.cls = cls;
        sourceBuilder = new Builder().size(DEFAULT_SEARCH_SIZE).timeout("30m");
        this.boolQuery = new BoolQuery.Builder().must(must -> must.match(m -> m.field("type").query(JsonObjectMapper.getType(cls))));
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
        this.boolQuery.must(must -> must.match(mq -> mq.field("status").query(status.toString())));
        return this;
    }

    @Override
    public Stream<? extends Entity> execute() throws IOException {
        BoolQuery boolQueryToBuild = boolQuery.build();
        sourceBuilder.index(indexesNames).query(q -> q.bool(boolQueryToBuild));
        this.boolQuery = new BoolQuery.Builder().must(boolQueryToBuild.must()).mustNot(boolQueryToBuild.mustNot());
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
        BoolQuery boolQueryToBuild = boolQuery.build();
        sourceBuilder.index(indexesNames).query(q -> q.bool(boolQueryToBuild));
        this.boolQuery = new BoolQuery.Builder().must(boolQueryToBuild.must()).mustNot(boolQueryToBuild.mustNot());
        if (nbSlices > 1) {
            sourceBuilder.slice(s -> s.id(String.valueOf(numSlice)).max(nbSlices));
        }
        if (scrollId == null) {
            SearchRequest searchRequest = sourceBuilder.scroll(KEEP_ALIVE).build();
            this.sourceBuilder = new Builder().size(DEFAULT_SEARCH_SIZE).timeout("30m")
                    .index(searchRequest.index()).slice(searchRequest.slice()).source(searchRequest.source()).size(searchRequest.size());
            SearchResponse<ObjectNode> search = client.search(searchRequest, ObjectNode.class);
            scrollId = search.scrollId();
            totalHits = search.hits().total().value();
            return resultStream(this.cls, () -> search.hits().hits().iterator());
        } else {
            ScrollResponse<ObjectNode> search = client.scroll(ScrollRequest.of(s -> s.scroll(KEEP_ALIVE).scrollId(scrollId)), ObjectNode.class);
            scrollId = search.scrollId();
            return resultStream(this.cls, () -> search.hits().hits().iterator());
        }
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
        this.boolQuery.mustNot(q -> q.constantScore(cs -> cs.filter(qt -> qt.terms(t -> t.field("nerTags").terms(tqf -> tqf.value(
                stream(nlpPipelines).map(Pipeline.Type::toString).map(FieldValue::of).collect(toList()))
        )))));
        return this;
    }

    @Override
    public Indexer.Searcher with(Pipeline.Type... nlpPipelines) {
        this.boolQuery.must(q -> q.constantScore(cs -> cs.filter(qt -> qt.terms(t -> t.field("nerTags").terms(tqf -> tqf.value(
                stream(nlpPipelines).map(Pipeline.Type::toString).map(FieldValue::of).collect(toList()))
        )))));
        return this;
    }

    @Override
    public Indexer.Searcher with(Tag... tags) {
        this.boolQuery.must(q -> q.constantScore(cs -> cs.filter(qt -> qt.terms(t -> t.field("tags").terms(tqf -> tqf.value(
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
        this.boolQuery = new BoolQuery.Builder().must(m -> m.withJson(new StringReader(jsonQuery.toString())));
        return this;
    }

    @Override
    public Indexer.Searcher with(String query, int fuzziness, boolean phraseMatches) {
        String queryString;
        if (phraseMatches) {
            queryString = "\"" + query + "\"" + (fuzziness == 0 ? "" : "~" + fuzziness);
        } else if (fuzziness > 0) {
            queryString = Stream.of(query.split(" ")).map(s -> s + "~" + fuzziness).collect(Collectors.joining(" "));
        } else {
            queryString = query;
        }
        this.boolQuery.must(m -> m.matchAll(ma -> ma));
        this.boolQuery.must(m -> m.queryString(qs -> qs.query(queryString)));
        return this;
    }

    @Override
    public Indexer.Searcher limit(int maxCount) {
        sourceBuilder.size(maxCount);
        return this;
    }

    @Override
    public Indexer.Searcher withFieldValues(String key, String... values) {
        if (values.length > 0) {
            this.boolQuery.must(m -> m.terms(t -> t.field(key).terms(tqf -> tqf.value(stream(values).map(FieldValue::of).collect(toList())))));
        }
        return this;
    }

    @Override
    public Indexer.Searcher withPrefixQuery(String key, String... values) {
        if (values.length == 0) {
            return this;
        }
        if (values.length == 1) {
            this.boolQuery.must(m -> m.prefix(p -> p.field(key).value(values[0])));
            return this;
        }
        BoolQuery.Builder innerQuery = new BoolQuery.Builder();
        Arrays.stream(values).forEach(v -> innerQuery.must(m -> m.prefix(p -> p.field(key).value(v))));
        this.boolQuery.must(m -> m.bool(innerQuery.build()));
        return this;
    }

    @Override
    public Indexer.Searcher thatMatchesFieldValue(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("[ match ] requires query value");
        }
        this.boolQuery.must(m -> m.match(q -> q.field(name).query(FieldValue.of(value.toString()))));
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
        return "boolQuery : " + boolQuery;
    }
}