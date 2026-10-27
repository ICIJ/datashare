package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Project;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Integer.MAX_VALUE;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Elasticsearch will not produce a version conflict on demand and racing a writer against the batch would only
 * buy a flaky test, so the conflict is simulated. The simulation stays faithful to Conflicts.Proceed: the
 * document that did not conflict really is updated and the conflicting one really is skipped, so the retry runs
 * unstubbed against a real index and the assertions read real documents back.
 *
 * Every indexer here keeps the default refresh policy, Refresh.False, the one production runs: a retry that
 * cannot see the writes of the attempt before it conflicts on the documents it just updated and never converges.
 */
public class ElasticsearchIndexerVersionConflictTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule
    public ExpectedException expected = ExpectedException.none();

    @Before
    public void setUp() throws Exception {
        ObjectNode document = JsonObjectMapper.getMapper().createObjectNode().put("type", "Document");
        document.putArray("tags").add("invoice");
        for (String id : List.of("doc1", "doc2")) {
            es.client.index(i -> i.index(es.getIndexName()).id(id).document(document).refresh(Refresh.True));
        }
    }

    @Test
    public void test_the_document_left_behind_by_a_conflict_is_tagged_by_the_retry() throws Exception {
        ElasticsearchClient conflicting = clientConflictingOnceOn("doc2");

        assertThat(indexerWith(conflicting).tag(index(), List.of("doc1", "doc2"), tag("urgent"))).isTrue();

        verify(conflicting, times(2)).updateByQuery(any(UpdateByQueryRequest.class));
        assertThat(tagsOf("doc2")).containsOnly("invoice", "urgent");
        // The retry replays the whole id list: the script is idempotent, so doc1 keeps one copy of the tag.
        assertThat(tagsOf("doc1")).containsOnly("invoice", "urgent");
    }

    @Test
    public void test_an_untag_conflict_is_cleared_by_the_retry_too() throws Exception {
        ElasticsearchIndexer indexer = indexerWith(clientConflictingOnceOn("doc2"));

        assertThat(indexer.untag(index(), List.of("doc1", "doc2"), tag("invoice"))).isTrue();

        assertThat(tagsOf("doc1")).isEmpty();
        assertThat(tagsOf("doc2")).isEmpty();
    }

    @Test
    public void test_a_version_conflict_that_never_clears_names_the_documents_left_behind() throws Exception {
        expected.expect(IOException.class);
        expected.expectMessage(es.getIndexName());
        expected.expectMessage("1 document(s) in version conflict");
        expected.expectMessage("[doc2]");

        indexerWith(clientAlwaysConflictingOn("doc2")).tag(index(), List.of("doc1", "doc2"), tag("urgent"));
    }

    @Test
    public void test_an_untag_that_never_clears_names_the_documents_still_carrying_the_tag() throws Exception {
        expected.expect(IOException.class);
        expected.expectMessage("[doc2]");

        indexerWith(clientAlwaysConflictingOn("doc2")).untag(index(), List.of("doc1", "doc2"), tag("invoice"));
    }

    @Test
    public void test_a_listing_that_fails_keeps_the_conflict_as_the_cause() throws Exception {
        ElasticsearchClient conflicting = clientAlwaysConflictingOn("doc2");
        doThrow(new IOException("cluster unreachable")).when(conflicting)
                                                       .search(any(SearchRequest.class), eq(ObjectNode.class));

        expected.expect(IOException.class);
        expected.expectMessage("1 document(s) in version conflict");
        expected.expectMessage("could not list them: cluster unreachable");

        indexerWith(conflicting).tag(index(), List.of("doc1", "doc2"), tag("urgent"));
    }

    // The tags field is lowercased by its normalizer while the scripts compare labels as they are: the documents
    // named have to be the ones the script would still change, not the ones a case insensitive match returns.
    @Test
    public void test_a_label_that_differs_only_by_case_names_the_documents_the_tag_is_missing_from()
            throws Exception {
        expected.expect(IOException.class);
        expected.expectMessage("[doc2]");

        indexerWith(clientAlwaysConflictingOn("doc2")).tag(index(), List.of("doc1", "doc2"), tag("INVOICE"));
    }

    @Test
    public void test_an_untag_of_a_label_that_differs_only_by_case_names_no_document() throws Exception {
        try {
            indexerWith(clientAlwaysConflictingOn("doc2")).untag(index(), List.of("doc1", "doc2"), tag("INVOICE"));
            fail("the batch never converged, it should have thrown");
        } catch (IOException e) {
            assertThat(e.getMessage()).contains("[]");
        }
    }

    @Test
    public void test_more_documents_left_behind_than_the_message_shows_says_so() throws Exception {
        List<String> documentIds = indexDocuments(21);

        expected.expect(IOException.class);
        expected.expectMessage("21 document(s) in version conflict");
        expected.expectMessage("and more, 20 shown");

        indexerWith(clientApplyingNothing(21)).tag(index(), documentIds, tag("urgent"));
    }

    @Test
    public void test_exactly_as_many_documents_as_the_message_shows_claims_nothing_more() throws Exception {
        List<String> documentIds = indexDocuments(20);

        try {
            indexerWith(clientApplyingNothing(20)).tag(index(), documentIds, tag("urgent"));
            fail("the batch left 20 documents behind, it should have thrown");
        } catch (IOException e) {
            assertThat(e.getMessage()).contains("doc20]").excludes("and more");
        }
    }

    // doc1 already carries the tag, so the script noops: nothing was updated and there is no conflict to clear.
    @Test
    public void test_a_batch_without_conflict_is_not_retried() throws Exception {
        ElasticsearchClient counting = spy(es.client);

        assertThat(indexerWith(counting).tag(index(), List.of("doc1"), tag("invoice"))).isFalse();

        verify(counting, times(1)).updateByQuery(any(UpdateByQueryRequest.class));
    }

    private Project index() {
        return project(es.getIndexName());
    }

    private List<String> indexDocuments(int count) throws IOException {
        List<String> documentIds = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String id = "doc" + i;
            es.client.index(r -> r.index(es.getIndexName()).id(id).document(
                    JsonObjectMapper.getMapper().createObjectNode().put("type", "Document")).refresh(Refresh.True));
            documentIds.add(id);
        }
        return documentIds;
    }

    // Answers every call the way Elasticsearch answers a batch where every document conflicted: nothing applied.
    private ElasticsearchClient clientApplyingNothing(int conflicts) throws IOException {
        ElasticsearchClient stalled = spy(es.client);
        doAnswer(invocation -> new UpdateByQueryResponse.Builder().total((long) conflicts).updated(0L)
                                                                  .versionConflicts((long) conflicts)
                                                                  .build()).when(stalled)
                                                                           .updateByQuery(
                                                                                   any(UpdateByQueryRequest.class));
        return stalled;
    }

    private ElasticsearchIndexer indexerWith(ElasticsearchClient client) {
        return new ElasticsearchIndexer(client, new PropertiesProvider());
    }

    private ElasticsearchClient clientConflictingOnceOn(String conflictingId) throws IOException {
        return clientConflictingOn(conflictingId, 1);
    }

    private ElasticsearchClient clientAlwaysConflictingOn(String conflictingId) throws IOException {
        return clientConflictingOn(conflictingId, MAX_VALUE);
    }

    /**
     * Answers the first conflictingAttempts update_by_query calls the way Elasticsearch answers one that hit a
     * conflict: the request is run for real on every document but the conflicting one, and the count of what was
     * skipped comes back in versionConflicts. Later calls go to the cluster untouched, so a retry given the
     * chance to converge is the real thing.
     */
    private ElasticsearchClient clientConflictingOn(String conflictingId, int conflictingAttempts) throws
            IOException {
        ElasticsearchClient conflicting = spy(es.client);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() >= conflictingAttempts) {
                return invocation.callRealMethod();
            }
            UpdateByQueryRequest batch = invocation.getArgument(0);
            UpdateByQueryResponse applied = es.client.updateByQuery(
                    r -> r.index(batch.index()).script(batch.script()).refresh(Boolean.TRUE.equals(batch.refresh()))
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
