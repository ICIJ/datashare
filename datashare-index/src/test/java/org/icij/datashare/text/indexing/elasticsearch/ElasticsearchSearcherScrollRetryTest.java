package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.ScrollRequest;
import co.elastic.clients.elasticsearch.core.ScrollResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.StatusLine;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.icij.datashare.text.Document;
import org.junit.Test;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A scroll page rejected by an Elasticsearch circuit breaker (HTTP 429) is retried instead of failing the scan. */
public class ElasticsearchSearcherScrollRetryTest {
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchSearcher searcher = new ElasticsearchQueryBuilderSearcher(client, List.of("project"), Document.class);

    @Test(timeout = 30000)
    public void test_scroll_retries_a_page_rejected_with_too_many_requests() throws Exception {
        ResponseException tooManyRequests = responseExceptionWithStatus(429);
        SearchResponse<ObjectNode> firstPage = emptySearchResponse();
        ScrollResponse<ObjectNode> nextPage = emptyScrollResponse();
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(firstPage);
        when(client.scroll(any(ScrollRequest.class), eq(ObjectNode.class))).thenThrow(tooManyRequests).thenReturn(nextPage);

        searcher.scroll("1m");
        searcher.scroll("1m");

        // without the retry the first 429 escapes and the scan stops, as SCANIDX did on the circuit breaker
        verify(client, times(2)).scroll(any(ScrollRequest.class), eq(ObjectNode.class));
    }

    @Test(timeout = 30000)
    public void test_scroll_does_not_retry_other_http_errors() throws Exception {
        ResponseException badRequest = responseExceptionWithStatus(400);
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenThrow(badRequest);

        try {
            searcher.scroll("1m");
            fail("a 400 is not transient and must reach the caller untouched");
        } catch (ResponseException e) {
            assertThat(e).isSameAs(badRequest);
        }
        verify(client, times(1)).search(any(SearchRequest.class), eq(ObjectNode.class));
    }

    private static ResponseException responseExceptionWithStatus(int statusCode) {
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(statusCode);
        Response response = mock(Response.class);
        when(response.getStatusLine()).thenReturn(statusLine);
        ResponseException exception = mock(ResponseException.class);
        when(exception.getResponse()).thenReturn(response);
        return exception;
    }

    private static SearchResponse<ObjectNode> emptySearchResponse() {
        return SearchResponse.of(r -> r.took(1).timedOut(false).scrollId("scroll-id")
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.total(t -> t.value(0).relation(TotalHitsRelation.Eq)).hits(List.of())));
    }

    private static ScrollResponse<ObjectNode> emptyScrollResponse() {
        return ScrollResponse.of(r -> r.took(1).timedOut(false).scrollId("scroll-id")
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.total(t -> t.value(0).relation(TotalHitsRelation.Eq)).hits(List.of())));
    }
}
