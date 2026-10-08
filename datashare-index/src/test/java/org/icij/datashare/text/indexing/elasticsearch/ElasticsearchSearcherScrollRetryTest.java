package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.ClearScrollRequest;
import co.elastic.clients.elasticsearch.core.ClearScrollResponse;
import co.elastic.clients.elasticsearch.core.ScrollRequest;
import co.elastic.clients.elasticsearch.core.ScrollResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.elasticsearch.client.ResponseException;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.time.DatashareTime;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A request rejected by an Elasticsearch circuit breaker (HTTP 429) is retried instead of failing the scan. */
public class ElasticsearchSearcherScrollRetryTest {
    @Rule
    public DatashareTimeRule time = new DatashareTimeRule();
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchSearcher searcher = new ElasticsearchQueryBuilderSearcher(client, List.of("project"), Document.class);

    @Test
    public void test_scroll_retries_a_first_page_rejected_with_too_many_requests() throws Exception {
        ResponseException tooManyRequests = responseExceptionWithStatus(429);
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class)))
                .thenThrow(tooManyRequests).thenReturn(emptySearchResponse());

        searcher.scroll("1m");

        verify(client, times(2)).search(any(SearchRequest.class), eq(ObjectNode.class));
    }

    @Test
    public void test_scroll_retries_a_next_page_rejected_with_too_many_requests() throws Exception {
        ResponseException tooManyRequests = responseExceptionWithStatus(429);
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(emptySearchResponse());
        when(client.scroll(any(ScrollRequest.class), eq(ObjectNode.class)))
                .thenThrow(tooManyRequests).thenReturn(emptyScrollResponse());

        searcher.scroll("1m");
        searcher.scroll("1m");

        verify(client, times(2)).scroll(any(ScrollRequest.class), eq(ObjectNode.class));
    }

    @Test
    public void test_scroll_gives_up_after_the_last_attempt_with_a_doubling_backoff() throws Exception {
        ResponseException tooManyRequests = responseExceptionWithStatus(429);
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenThrow(tooManyRequests);
        long startMillis = DatashareTime.getInstance().currentTimeMillis();

        assertThat(scrollFailure()).isSameAs(tooManyRequests);

        verify(client, times(5)).search(any(SearchRequest.class), eq(ObjectNode.class));
        assertThat(DatashareTime.getInstance().currentTimeMillis() - startMillis).isEqualTo(1000 + 2000 + 4000 + 8000);
    }

    @Test
    public void test_scroll_does_not_retry_other_http_errors() throws Exception {
        ResponseException serviceUnavailable = responseExceptionWithStatus(503);
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenThrow(serviceUnavailable);

        assertThat(scrollFailure()).isSameAs(serviceUnavailable);

        verify(client, times(1)).search(any(SearchRequest.class), eq(ObjectNode.class));
    }

    @Test(timeout = 30000)
    public void test_scroll_interrupted_while_waiting_to_retry_reports_the_interrupt() throws Exception {
        DatashareTime.setMockTime(false);
        ResponseException tooManyRequests = responseExceptionWithStatus(429);
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenThrow(tooManyRequests);
        Thread.currentThread().interrupt();

        Exception interrupted = scrollFailure();

        assertThat(Thread.interrupted()).isTrue();
        assertThat(interrupted).isInstanceOf(RuntimeException.class);
        assertThat(interrupted.getCause()).isInstanceOf(InterruptedException.class);
    }

    @Test
    public void test_clear_scroll_retries_a_request_rejected_with_too_many_requests() throws Exception {
        ResponseException tooManyRequests = responseExceptionWithStatus(429);
        when(client.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(emptySearchResponse());
        when(client.clearScroll(any(ClearScrollRequest.class)))
                .thenThrow(tooManyRequests).thenReturn(ClearScrollResponse.of(r -> r.succeeded(true).numFreed(1)));
        searcher.scroll("1m");

        searcher.clearScroll();

        verify(client, times(2)).clearScroll(any(ClearScrollRequest.class));
    }

    @Test
    public void test_clear_scroll_without_an_open_scroll_sends_nothing() throws Exception {
        searcher.clearScroll();

        verify(client, never()).clearScroll(any(ClearScrollRequest.class));
    }

    private Exception scrollFailure() {
        try {
            searcher.scroll("1m");
        } catch (Exception e) {
            return e;
        }
        fail("the scroll was expected to fail");
        return null;
    }

    private static ResponseException responseExceptionWithStatus(int statusCode) {
        ResponseException exception = mock(ResponseException.class, RETURNS_DEEP_STUBS);
        when(exception.getResponse().getStatusLine().getStatusCode()).thenReturn(statusCode);
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
