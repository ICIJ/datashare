package org.icij.datashare.text.indexing.elasticsearch;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.test.ElasticsearchRule;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.text.Tag.tag;

/**
 * An update_by_query that failed on some documents answers 4xx with a body the client still decodes, so the
 * failures reach the indexer rather than the transport. A default_pipeline that writes a string into the integer
 * field contentLength for doc2 only reproduces that: doc1 is updated, doc2 is rejected.
 */
public class ElasticsearchIndexerBatchFailureTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule
    public ExpectedException expected = ExpectedException.none();
    private static final String PIPELINE = "reject-doc2";
    private final ElasticsearchIndexer indexer =
            new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);

    @Before
    public void setUp() throws Exception {
        es.client.ingest().putPipeline(p -> p.id(PIPELINE).processors(pr -> pr.set(
                s -> s.if_("ctx._id == 'doc2'").field("contentLength").value(JsonData.of("not-a-number")))));
        es.client.indices().putSettings(s -> s.index(es.getIndexName()).settings(st -> st.defaultPipeline("_none")));
        ObjectNode document = JsonObjectMapper.getMapper().createObjectNode().put("type", "Document")
                                              .put("contentLength", 1);
        document.putArray("tags").add("invoice");
        for (String id : List.of("doc1", "doc2")) {
            es.client.index(i -> i.index(es.getIndexName()).id(id).document(document).refresh(Refresh.True));
        }
        es.client.indices().putSettings(s -> s.index(es.getIndexName()).settings(st -> st.defaultPipeline(PIPELINE)));
    }

    @AfterClass
    public static void tearDown() throws Exception {
        es.client.indices().putSettings(s -> s.index(es.getIndexName()).settings(st -> st.defaultPipeline("_none")));
        es.client.ingest().deletePipeline(p -> p.id(PIPELINE));
    }

    @Test
    public void test_a_partially_applied_batch_is_reported() throws Exception {
        expected.expect(IOException.class);
        expected.expectMessage("updated 1 of 2");
        expected.expectMessage("doc2");
        expected.expectMessage("document_parsing_exception");
        expected.expectMessage("contentLength");

        indexer.tag(project(es.getIndexName()), List.of("doc1", "doc2"), tag("urgent"));
    }

    @Test
    public void test_an_untag_failure_is_reported_too() throws Exception {
        expected.expect(IOException.class);
        expected.expectMessage("doc2");
        expected.expectMessage("document_parsing_exception");

        indexer.untag(project(es.getIndexName()), List.of("doc1", "doc2"), tag("invoice"));
    }

    // Tagging documents that already carry the tag updates nothing: it must return false without throwing.
    @Test
    public void test_nothing_to_update_is_not_a_failure() throws Exception {
        assertThat(indexer.tag(project(es.getIndexName()), List.of("doc1"), tag("invoice"))).isFalse();
    }

    @Test
    public void test_an_update_that_changed_something_is_true() throws Exception {
        assertThat(indexer.tag(project(es.getIndexName()), List.of("doc1"), tag("urgent"))).isTrue();
    }
}
