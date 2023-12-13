package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.icij.datashare.Entity;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

class ElasticsearchQueryBuilderSearcher extends ElasticsearchSearcher implements Indexer.QueryBuilderSearcher {
    final BoolQuery.Builder boolQueryBuilder;
    final String stringQuery;

    ElasticsearchQueryBuilderSearcher(ElasticsearchClient client, final List<String> indexesNames, final Class<? extends Entity> cls) {
        super(client, indexesNames, cls, null);
        this.boolQueryBuilder = new BoolQuery.Builder().must(must -> must.match(m -> m.field("type").query(JsonObjectMapper.getType(cls))));
        this.stringQuery = null;
    }

    ElasticsearchQueryBuilderSearcher(ElasticsearchClient client, final List<String> indexesNames, final Class<? extends Entity> cls, SearchQuery query) {
        super(client, indexesNames, cls, null);
        this.boolQueryBuilder = new BoolQuery.Builder().must(must -> must.match(m -> m.field("type").query(JsonObjectMapper.getType(cls))));
        this.stringQuery = query.toString();
    }
    
    @Override
    public Indexer.QueryBuilderSearcher ofStatus(Document.Status status) {
        this.boolQueryBuilder.must(must -> must.match(mq -> mq.field("status").query(status.toString())));
        return this;
    }

    @Override
    public Stream<? extends Entity> execute() throws IOException {
        if(stringQuery != null) {
            getBoolQueryBuilder(stringQuery);
        }
        sourceBuilder.index(indexesNames).query(q -> q.bool(boolQueryBuilder.build()));
        SearchRequest searchRequest = sourceBuilder.build();
        SearchResponse<ObjectNode> search = client.search(searchRequest, ObjectNode.class);
        return resultStream(this.cls, () -> search.hits().hits().iterator());
    }

    @Override
    protected BoolQuery.Builder getBoolQueryBuilder(String query) {
        return query != null ? boolQueryBuilder.must(m -> m.matchAll(ma -> ma))
                .must(m -> m.queryString(qs -> qs.query(buildQueryString(query, fuzziness, phraseMatches, "\"")))):
                boolQueryBuilder;
    }

    @Override
    protected String queryAsString(String unused) {
        return stringQuery;
    }

    @Override
    public Indexer.QueryBuilderSearcher without(Pipeline.Type... nlpPipelines) {
        this.boolQueryBuilder.mustNot(q -> q.constantScore(cs -> cs.filter(qt -> qt.terms(t -> t.field("nerTags").terms(tqf -> tqf.value(
                stream(nlpPipelines).map(Pipeline.Type::toString).map(FieldValue::of).collect(toList()))
        )))));
        return this;
    }

    @Override
    public Indexer.QueryBuilderSearcher with(Pipeline.Type... nlpPipelines) {
        this.boolQueryBuilder.must(q -> q.constantScore(cs -> cs.filter(qt -> qt.terms(t -> t.field("nerTags").terms(tqf -> tqf.value(
                stream(nlpPipelines).map(Pipeline.Type::toString).map(FieldValue::of).collect(toList()))
        )))));
        return this;
    }

    @Override
    public Indexer.QueryBuilderSearcher with(Tag... tags) {
        this.boolQueryBuilder.must(q -> q.constantScore(cs -> cs.filter(qt -> qt.terms(t -> t.field("tags").terms(tqf -> tqf.value(
                stream(tags).map(tag -> FieldValue.of(tag.label)).collect(toList()))
        )))));
        return this;
    }

    @Override
    public Indexer.QueryBuilderSearcher withFieldValues(String key, String... values) {
        if (values.length > 0) {
            this.boolQueryBuilder.must(m -> m.terms(t -> t.field(key).terms(tqf -> tqf.value(stream(values).map(FieldValue::of).collect(toList())))));
        }
        return this;
    }

    @Override
    public Indexer.QueryBuilderSearcher withPrefixQuery(String key, String... values) {
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
    public Indexer.QueryBuilderSearcher thatMatchesFieldValue(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("[ match ] requires query value");
        }
        this.boolQueryBuilder.must(m -> m.match(q -> q.field(name).query(FieldValue.of(value.toString()))));
        return this;
    }

    @Override
    public String toString() {
        return "boolQuery : " + boolQueryBuilder;
    }
}