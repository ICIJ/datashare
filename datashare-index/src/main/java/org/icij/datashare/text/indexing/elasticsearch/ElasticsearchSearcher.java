package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
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
import jakarta.json.JsonException;
import java.util.Objects;
import org.icij.datashare.Entity;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.utils.JsonUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static co.elastic.clients.elasticsearch.core.SearchRequest.Builder;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.text.indexing.Indexer.*;
import static org.icij.datashare.text.indexing.ScrollQueryBuilder.createScrollQuery;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.DEFAULT_SEARCH_SIZE;

class ElasticsearchSearcher implements Indexer.Searcher {
    protected final List<String> indexesNames;
    protected final ElasticsearchClient client;
    protected final Class<? extends Entity> cls;

    final Builder sourceBuilder;
    private String scrollId;
    private SearchRequest scrollSearchRequest;
    private long totalHits;
    private final JsonNode jsonBoolQuery;
    private final static String TEMPLATE_QUERY = "<query>";

    protected int fuzziness = 0;
    protected boolean phraseMatches = false;

    ElasticsearchSearcher(ElasticsearchClient client, final List<String> indexesNames, final Class<? extends Entity> cls, JsonNode boolQuery) {
        this.client = client;
        this.indexesNames = indexesNames;
        this.cls = cls;
        sourceBuilder = new Builder().size(DEFAULT_SEARCH_SIZE).timeout("30m");
        this.jsonBoolQuery = boolQuery;
    }

    static Stream<Hit<ObjectNode>> searchHitStream(Iterable<Hit<ObjectNode>> searchHitIterable) {
        return StreamSupport.stream(searchHitIterable.spliterator(), false);
    }

    static <T extends Entity> Stream<T> resultStream(Class<T> cls, Iterable<Hit<ObjectNode>> iterable) {
        return searchHitStream(iterable).map(hit -> hitToObject(hit, cls));
    }

    static <T extends Entity> T hitToObject(Hit<ObjectNode> searchHit, Class<T> cls) {
        return JsonObjectMapper.getObject(searchHit.id(), searchHit.index(), JsonUtils.nodeToMap(searchHit.source()), cls);
    }

    @Override
    public Stream<? extends Entity> execute() throws IOException {
        return getStream(this.jsonBoolQuery.toString());
    }

    @Override
    public Stream<? extends Entity> execute(String stringQuery) throws IOException {
        String queryString = buildQueryString(stringQuery, fuzziness, phraseMatches, "\\\\\"");
        final String queryBody = jsonBoolQuery.toString().replaceAll(TEMPLATE_QUERY,queryString);
        return getStream(queryBody);
    }

    protected Stream<? extends Entity> getStream(String queryBody) throws IOException {
        BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder().must(m -> m.withJson(new StringReader(queryBody)));
        sourceBuilder.index(indexesNames).query(q -> q.bool(boolQueryBuilder.build()));
        SearchResponse<ObjectNode> search = client.search(sourceBuilder.build(), ObjectNode.class);
        return resultStream(this.cls, () -> search.hits().hits().iterator());
    }

    @Override
    public Stream<? extends Entity> scroll(String duration) throws IOException {
        return scroll(createScrollQuery().withDuration(duration).withSlices(0,0).build());
    }
    @Override
    public Stream<? extends Entity> scroll(String duration, String stringQuery) throws IOException {
        return scroll(createScrollQuery().withDuration(duration).withStringQuery(stringQuery).withSlices(0,0).build());
    }

    protected BoolQuery.Builder getBoolQueryBuilder(String query) throws JsonException {
        return new BoolQuery.Builder().must(m -> m.withJson(new StringReader(query)));
    }

    protected String queryAsString(String queryString) {
        if (isTemplate() && queryString != null) {
            String replacement = buildQueryString(queryString, fuzziness, phraseMatches, "\\\\\"");
            return jsonBoolQuery.toString().replaceAll(TEMPLATE_QUERY, replacement);
        } else {
            return jsonBoolQuery.toString();
        }
    }

    private boolean isTemplate() {
        return jsonBoolQuery.toString().contains(TEMPLATE_QUERY);
    }

    @Override
    public Stream<? extends Entity> scroll(ScrollQuery scrollQuery) throws IOException {
        ResponseBody<ObjectNode> response;
        if (scrollSearchRequest == null) {
            BoolQuery.Builder boolQueryBuilder = getBoolQueryBuilder(queryAsString(scrollQuery.getStringQuery()));
            sourceBuilder.index(indexesNames).query(q -> q.bool(boolQueryBuilder.build()));
            if (scrollQuery.getNbSlices() > 1) {
                sourceBuilder.slice(s -> s.id(String.valueOf(scrollQuery.getNumSlice())).max(scrollQuery.getNbSlices()));
            }
            scrollSearchRequest = sourceBuilder.scroll(Time.of(t -> t.time(scrollQuery.getDuration()))).build();
            response = client.search(scrollSearchRequest, ObjectNode.class);
            totalHits = Objects.requireNonNull(response.hits().total()).value();
        } else if (scrollQuery.getStringQuery() == null) {
            response = client.scroll(ScrollRequest.of(s -> s.scroll(Time.of(t -> t.time(scrollQuery.getDuration())))
                    .scrollId(ofNullable(scrollId)
                            .orElseThrow(() -> new IllegalStateException("ScrollId must have been cleared")))), ObjectNode.class);
        } else {
            throw new IllegalStateException("cannot change query when scroll is pending");
        }
        scrollId = response.scrollId();
        return resultStream(this.cls, () -> response.hits().hits().iterator());
    }

    @Override
    public Indexer.Searcher withSource(String... fields) {
        sourceBuilder.source(s -> s.filter(f -> f.includes(stream(fields).collect(Collectors.toList()))));
        return this;
    }

    @Override
    public Indexer.Searcher with(int fuzziness, boolean phraseMatches) {
        this.fuzziness = fuzziness;
        this.phraseMatches = phraseMatches;
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

    protected static String buildQueryString(String query, int fuzziness, boolean phraseMatches, String phraseMatchDoubleQuotes) {
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
    public Searcher sort(String field, SortOrder order) {
        sourceBuilder.sort(builder -> builder.field(fieldBuilder -> fieldBuilder.field(field).order(esSortOrder(order))));
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
        return "query : " + jsonBoolQuery;
    }

    private co.elastic.clients.elasticsearch._types.SortOrder esSortOrder(SortOrder sortOrder) {
        return switch (sortOrder) {
            case ASC -> co.elastic.clients.elasticsearch._types.SortOrder.Asc;
            case DESC -> co.elastic.clients.elasticsearch._types.SortOrder.Desc;
        };
    }
}