package org.icij.datashare.text.indexing.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.slice.SliceBuilder;
import org.icij.datashare.Entity;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Tag;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.DEFAULT_SEARCH_SIZE;

class ElasticsearchSearcher implements Indexer.Searcher {
    static final TimeValue KEEP_ALIVE = new TimeValue(60000);
    private BoolQueryBuilder boolQuery;
    private final RestHighLevelClient client;
    private final List<String> indexesNames;
    private final Class<? extends Entity> cls;
    private final SearchSourceBuilder sourceBuilder;
    private String scrollId;
    private long totalHits;

    ElasticsearchSearcher(RestHighLevelClient client, ElasticsearchConfiguration config, final List<String> indexesNames, final Class<? extends Entity> cls) {
        this.client = client;
        this.indexesNames = indexesNames;
        this.cls = cls;
        sourceBuilder = new SearchSourceBuilder().size(DEFAULT_SEARCH_SIZE).timeout(new TimeValue(30, TimeUnit.MINUTES));
        this.boolQuery = boolQuery().must(matchQuery("type", JsonObjectMapper.getType(cls)));
    }

    static Stream<SearchHit> searchHitStream(Iterable<SearchHit> searchHitIterable) {
        return StreamSupport.stream(searchHitIterable.spliterator(), false);
    }

    static <T extends Entity> Stream<T> resultStream(Class<T> cls, Iterable<SearchHit> iterable) {
        return searchHitStream(iterable).map(hit -> hitToObject(hit, cls));
    }

    static <T extends Entity> T hitToObject(SearchHit searchHit, Class<T> cls) {
        return JsonObjectMapper.getObject(searchHit.getId(), searchHit.getIndex(), searchHit.getSourceAsMap(), cls);
    }
    
    @Override
    public Indexer.Searcher ofStatus(Document.Status status) {
        this.boolQuery.must(matchQuery("status", status.toString()));
        return this;
    }

    @Override
    public Stream<? extends Entity> execute() throws IOException {
        sourceBuilder.query(boolQuery);
        Object[] indexesArray = indexesNames.toArray();
        SearchRequest searchRequest = new SearchRequest(Arrays.copyOf(indexesArray, indexesArray.length, String[].class), sourceBuilder);
        SearchResponse search = client.search(searchRequest, RequestOptions.DEFAULT);
        return resultStream(this.cls, () -> search.getHits().iterator());
    }

    @Override
    public Stream<? extends Entity> scroll() throws IOException {
        return scroll(0, 0);
    }

    @Override
    public Stream<? extends Entity> scroll(int numSlice, int nbSlices) throws IOException {
        sourceBuilder.query(boolQuery);
        if (nbSlices > 1) {
            sourceBuilder.slice(new SliceBuilder(numSlice, nbSlices));
        }
        SearchResponse search;
        if (scrollId == null) {
            Object[] indexesArray = indexesNames.toArray();
            SearchRequest searchRequest = new SearchRequest(Arrays.copyOf(indexesArray, indexesArray.length, String[].class), sourceBuilder).scroll(KEEP_ALIVE);
            search = client.search(searchRequest, RequestOptions.DEFAULT);
            scrollId = search.getScrollId();
            totalHits = search.getHits().getTotalHits().value;
        } else {
            search = client.scroll(new SearchScrollRequest(scrollId).scroll(KEEP_ALIVE), RequestOptions.DEFAULT);
            scrollId = search.getScrollId();
        }
        return resultStream(this.cls, () -> search.getHits().iterator());
    }

    @Override
    public Indexer.Searcher withSource(String... fields) {
        sourceBuilder.fetchSource(fields, new String[]{});
        return this;
    }

    public Indexer.Searcher withoutSource(String... fields) {
        this.sourceBuilder.fetchSource(new String[]{"*"}, fields);
        return this;
    }

    @Override
    public Indexer.Searcher withSource(boolean source) {
        sourceBuilder.fetchSource(false);
        return this;
    }

    @Override
    public Indexer.Searcher without(Pipeline.Type... nlpPipelines) {
        boolQuery.mustNot(new ConstantScoreQueryBuilder(new TermsQueryBuilder("nerTags",
                stream(nlpPipelines).map(Pipeline.Type::toString).collect(toList()))));
        return this;
    }

    @Override
    public Indexer.Searcher with(Pipeline.Type... nlpPipelines) {
        boolQuery.must(new ConstantScoreQueryBuilder(new TermsQueryBuilder("nerTags",
                stream(nlpPipelines).map(Pipeline.Type::toString).collect(toList()))));
        return this;
    }

    @Override
    public Indexer.Searcher with(Tag... tags) {
        this.boolQuery.must(new ConstantScoreQueryBuilder(new TermsQueryBuilder("tags",
                stream(tags).map(t -> t.label).collect(toList()))));
        return this;
    }

    @Override
    public Indexer.Searcher with(String query) {
        return with(query, 0, false);
    }

    @Override
    public Indexer.Searcher set(JsonNode jsonQuery) {
        this.boolQuery = new BoolQueryBuilder().must(new WrapperQueryBuilder(jsonQuery.toString()));
        return this;
    }

    @Override
    public Indexer.Searcher with(String query, int fuzziness, boolean phraseMatches) {
        String queryString = query;
        if (phraseMatches) {
            queryString = "\"" + query + "\"" + (fuzziness == 0 ? "" : "~" + fuzziness);
        } else if (fuzziness > 0) {
            queryString = Stream.of(query.split(" ")).map(s -> s + "~" + fuzziness).collect(Collectors.joining(" "));
        }
        this.boolQuery.must(new MatchAllQueryBuilder());
        this.boolQuery.must(new QueryStringQueryBuilder(queryString));
        return this;
    }

    @Override
    public Indexer.Searcher limit(int maxCount) {
        sourceBuilder.size(maxCount);
        return this;
    }

    @Override
    public Indexer.Searcher withFieldValues(String key, String... values) {
        if (values.length > 0) this.boolQuery.must(termsQuery(key, values));
        return this;
    }

    @Override
    public Indexer.Searcher withPrefixQuery(String key, String... values) {
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
    public Indexer.Searcher thatMatchesFieldValue(String name, Object value) {
        this.boolQuery.must(matchQuery(name, value));
        return this;
    }

    @Override
    public void clearScroll() throws IOException {
        ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
        clearScrollRequest.addScrollId(scrollId);
        this.client.clearScroll(clearScrollRequest, RequestOptions.DEFAULT);
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