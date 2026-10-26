package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import org.icij.datashare.PropertiesProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A document modified between the search and the update of an update_by_query is counted in version_conflicts and
 * left out of failures, so a real cluster cannot be made to produce one on demand: the client is mocked here. Both
 * tag scripts are idempotent, which is what makes re-running the request a valid answer to a conflict.
 */
public class ElasticsearchIndexerVersionConflictTest {
    @Rule
    public ExpectedException expected = ExpectedException.none();
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(client, new PropertiesProvider());

    @Test
    public void test_a_version_conflict_is_retried_until_the_documents_are_updated() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(response(1, 1), response(1, 0));

        assertThat(indexer.tag(project("test-datashare"), List.of("doc1", "doc2"), tag("urgent"))).isTrue();

        verify(client, times(2)).updateByQuery(any(UpdateByQueryRequest.class));
    }

    @Test
    public void test_a_version_conflict_that_never_clears_is_reported() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(response(1, 1));

        expected.expect(IOException.class);
        expected.expectMessage("1 document(s) in version conflict");
        expected.expectMessage("test-datashare");

        indexer.tag(project("test-datashare"), List.of("doc1", "doc2"), tag("urgent"));
    }

    @Test
    public void test_an_untag_conflict_is_retried_too() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(response(0, 2), response(2, 0));

        assertThat(indexer.untag(project("test-datashare"), List.of("doc1", "doc2"), tag("invoice"))).isTrue();

        verify(client, times(2)).updateByQuery(any(UpdateByQueryRequest.class));
    }

    // A batch where nothing needed updating still answers false, without a retry: there is no conflict to clear.
    @Test
    public void test_a_batch_without_conflict_is_not_retried() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(response(0, 0));

        assertThat(indexer.tag(project("test-datashare"), List.of("doc1"), tag("invoice"))).isFalse();

        verify(client, times(1)).updateByQuery(any(UpdateByQueryRequest.class));
    }

    private static UpdateByQueryResponse response(int updated, int versionConflicts) {
        return new UpdateByQueryResponse.Builder().total(2L).updated((long) updated)
                                                  .versionConflicts((long) versionConflicts).build();
    }
}
