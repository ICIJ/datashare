package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.test.ElasticsearchRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A document modified between the search and the update of an update_by_query is counted in version_conflicts and
 * left out of failures. Elasticsearch will not produce one on demand and racing a writer against the batch would
 * only buy a flaky test, so the conflict is simulated here. The simulation stays faithful to Conflicts.Proceed:
 * the document that did not conflict really is updated in the cluster and the conflicting one really is skipped,
 * so the retry runs unstubbed against a real index and the assertions read real documents back.
 */
public class ElasticsearchIndexerVersionConflictTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule
    public ExpectedException expected = ExpectedException.none();
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(client, new PropertiesProvider());

    @Before
    public void setUp() throws Exception {
        ObjectNode document = JsonObjectMapper.getMapper().createObjectNode().put("type", "Document");
        document.putArray("tags").add("invoice");
        for (String id : List.of("doc1", "doc2")) {
            es.client.index(i -> i.index(es.getIndexName()).id(id).document(document).refresh(Refresh.True));
        }
    }

    // The conflicting document is the one the batch left behind: the retry has to be what puts the tag on it.
    @Test
    public void test_the_document_left_behind_by_a_conflict_is_tagged_by_the_retry() throws Exception {
        ElasticsearchClient conflicting = clientConflictingOnceOn("doc2");
        ElasticsearchIndexer realIndexer =
                new ElasticsearchIndexer(conflicting, new PropertiesProvider()).withRefresh(Refresh.True);

        assertThat(realIndexer.tag(project(es.getIndexName()), List.of("doc1", "doc2"), tag("urgent"))).isTrue();

        verify(conflicting, times(2)).updateByQuery(any(UpdateByQueryRequest.class));
        assertThat(tagsOf("doc2")).containsOnly("invoice", "urgent");
        // The retry replays the whole id list: the script is idempotent, so doc1 keeps one copy of the tag.
        assertThat(tagsOf("doc1")).containsOnly("invoice", "urgent");
    }

    @Test
    public void test_an_untag_conflict_is_cleared_by_the_retry_too() throws Exception {
        ElasticsearchClient conflicting = clientConflictingOnceOn("doc2");
        ElasticsearchIndexer realIndexer =
                new ElasticsearchIndexer(conflicting, new PropertiesProvider()).withRefresh(Refresh.True);

        assertThat(realIndexer.untag(project(es.getIndexName()), List.of("doc1", "doc2"), tag("invoice"))).isTrue();

        assertThat(tagsOf("doc1")).isEmpty();
        assertThat(tagsOf("doc2")).isEmpty();
    }

    @Test
    public void test_a_version_conflict_that_never_clears_is_reported() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(
                new UpdateByQueryResponse.Builder().total(2L).updated(1L).versionConflicts(1L).build());

        expected.expect(IOException.class);
        expected.expectMessage("1 document(s) in version conflict");
        expected.expectMessage(es.getIndexName());

        indexer.tag(project(es.getIndexName()), List.of("doc1", "doc2"), tag("urgent"));
    }

    // A batch where nothing needed updating still answers false, without a retry: there is no conflict to clear.
    @Test
    public void test_a_batch_without_conflict_is_not_retried() throws Exception {
        when(client.updateByQuery(any(UpdateByQueryRequest.class))).thenReturn(
                new UpdateByQueryResponse.Builder().total(1L).updated(0L).versionConflicts(0L).build());

        assertThat(indexer.tag(project(es.getIndexName()), List.of("doc1"), tag("invoice"))).isFalse();

        verify(client, times(1)).updateByQuery(any(UpdateByQueryRequest.class));
    }

    /**
     * Answers the first update_by_query the way Elasticsearch answers one that hit a conflict: the request is run
     * for real on every document but the conflicting one, and the count of what was skipped comes back in
     * versionConflicts. Later calls go to the cluster untouched, so the retry is the real thing.
     */
    private ElasticsearchClient clientConflictingOnceOn(String conflictingId) throws IOException {
        ElasticsearchClient conflicting = spy(es.client);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() > 0) {
                return invocation.callRealMethod();
            }
            UpdateByQueryRequest batch = invocation.getArgument(0);
            UpdateByQueryResponse applied = es.client.updateByQuery(
                    r -> r.index(batch.index()).script(batch.script()).refresh(true)
                          .query(q -> q.bool(b -> b.must(batch.query()).mustNot(
                                  mn -> mn.ids(ids -> ids.values(conflictingId))))));
            return new UpdateByQueryResponse.Builder().total(applied.total() + 1).updated(applied.updated())
                                                      .versionConflicts(1L).build();
        }).when(conflicting).updateByQuery(any(UpdateByQueryRequest.class));
        return conflicting;
    }

    private List<String> tagsOf(String id) throws IOException {
        ObjectNode source = es.client.get(g -> g.index(es.getIndexName()).id(id), ObjectNode.class).source();
        List<String> tags = new ArrayList<>();
        source.withArray("tags").forEach((JsonNode tag) -> tags.add(tag.asText()));
        return tags;
    }
}
