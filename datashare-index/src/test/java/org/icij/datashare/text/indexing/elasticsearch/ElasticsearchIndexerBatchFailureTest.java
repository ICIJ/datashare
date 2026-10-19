package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import org.icij.datashare.PropertiesProvider;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An update_by_query can answer 200 and still have failed on some documents. A real cluster will not produce that
 * on demand, Conflicts.Proceed turns version conflicts into a counter rather than a failure, so the response is
 * built here and handed to the indexer through a mocked client.
 */
public class ElasticsearchIndexerBatchFailureTest {
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(client, new PropertiesProvider());

    private static UpdateByQueryResponse response(long updated, boolean withFailure) {
        return UpdateByQueryResponse.of(r -> {
            r.updated(updated).took(1L).timedOut(false).total(1L).batches(1L).noops(0L).deleted(0L)
             .versionConflicts(0L).retries(t -> t.bulk(0L).search(0L)).throttledMillis(0L)
             .requestsPerSecond(-1.0f).throttledUntilMillis(0L);
            if (withFailure) {
                r.failures(f -> f.index("prj").id("doc1").status(400).cause(
                        ErrorCause.of(c -> c.type("mapper_parsing_exception").reason("failed to parse field [tags]"))));
            }
            return r;
        });
    }

    @Test
    public void test_a_failed_document_is_reported_instead_of_being_swallowed() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(response(0, true));

        try {
            indexer.tag(project("prj"), List.of("doc1"), tag("invoice"));
            throw new AssertionError("expected the failure to be reported");
        } catch (IOException e) {
            assertThat(e.getMessage()).contains("doc1").contains("mapper_parsing_exception");
        }
    }

    @Test
    public void test_an_untag_failure_is_reported_too() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(response(0, true));

        try {
            indexer.untag(project("prj"), List.of("doc1"), tag("invoice"));
            throw new AssertionError("expected the failure to be reported");
        } catch (IOException e) {
            assertThat(e.getMessage()).contains("doc1");
        }
    }

    // Re-tagging a document that already carries the tag updates nothing and is not a failure. PUT
    // /:project/documents/tags/:docId relies on this to answer 200 rather than 201, so it must stay a plain false.
    @Test
    public void test_nothing_to_update_is_not_a_failure() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(response(0, false));

        assertThat(indexer.tag(project("prj"), List.of("doc1"), tag("invoice"))).isFalse();
    }

    @Test
    public void test_an_update_that_changed_something_is_true() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(response(1, false));

        assertThat(indexer.tag(project("prj"), List.of("doc1"), tag("invoice"))).isTrue();
    }
}
