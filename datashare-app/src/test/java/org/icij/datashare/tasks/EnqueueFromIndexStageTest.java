package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.ScopedStageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.junit.Assert.fail;

public class EnqueueFromIndexStageTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final ElasticsearchIndexer indexer =
            new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    private final EnqueueFromIndexStage stage = new EnqueueFromIndexStage(indexer);
    private final List<List<String>> emissions = new ArrayList<>();
    private boolean cancelled;
    private boolean cancelAfterFirstEmission;

    @After
    public void tearDown() throws IOException {
        es.removeAll();
    }

    @Test
    public void test_emits_the_reference_of_every_document_and_counts_them() throws Exception {
        indexDocs(5);

        StageResult result = stage.process(List.of(), ctx(Map.of("stages", "ENQUEUEIDX,ARTIFACT")));

        assertThat(result).isEqualTo(new StageResult(5, 0, 0, 0));
        assertThat(emitted()).containsOnly("doc0", "doc1", "doc2", "doc3", "doc4");
    }

    @Test
    public void test_emits_one_batch_per_scroll_page() throws Exception {
        indexDocs(5);

        stage.process(List.of(), ctx(Map.of("stages", "ENQUEUEIDX,ARTIFACT", "scrollSize", "2")));

        assertThat(emissions).hasSize(3);
        assertThat(emissions.getFirst()).hasSize(2);
        assertThat(emitted()).hasSize(5);
    }

    @Test
    public void test_next_stage_is_read_from_the_stages_of_the_options() throws Exception {
        indexDocs(3);

        // with NLP after ENQUEUEIDX the documents already processed by the pipeline are excluded
        stage.process(List.of(), ctx(Map.of("stages", "ENQUEUEIDX,NLP", "nlpPipeline", Pipeline.Type.CORENLP.name())));

        assertThat(emitted()).isEmpty();
    }

    @Test
    public void test_next_stage_option_overrides_the_stages_chain() throws Exception {
        indexDocs(3);

        stage.process(List.of(), ctx(Map.of("stages", "ENQUEUEIDX,NLP", "nextStage", "ARTIFACT",
                                            "nlpPipeline", Pipeline.Type.CORENLP.name())));

        assertThat(emitted()).hasSize(3);
    }

    @Test
    public void test_unknown_next_stage_is_rejected_before_anything_is_emitted() throws Exception {
        indexDocs(3);

        try {
            stage.process(List.of(), ctx(Map.of("nextStage", "NOT_A_STAGE")));
            fail("an unknown nextStage value must fail the run");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("NOT_A_STAGE");
        }
        assertThat(emissions).isEmpty();
    }

    @Test
    public void test_next_stage_not_consuming_id_is_rejected() {
        try {
            stage.process(List.of(), ctx(Map.of("nextStage", "CREATENLPBATCHESFROMIDX")));
            fail("a stage with no queue consumer must be rejected");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("CREATENLPBATCHESFROMIDX");
        }
    }

    @Test
    public void test_cancellation_stops_the_scroll_and_reports_what_was_left() throws Exception {
        indexDocs(6);
        cancelAfterFirstEmission = true;

        StageResult result = stage.process(List.of(), ctx(Map.of("stages", "ENQUEUEIDX,ARTIFACT", "scrollSize", "2")));

        assertThat(emissions).hasSize(1);
        assertThat(result).isEqualTo(new StageResult(2, 0, 4, 0));
    }

    private void indexDocs(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            indexer.add(es.getIndexName(), createDoc("doc" + i).with(Pipeline.Type.CORENLP).with(project(es.getIndexName()))
                                                               .build());
        }
    }

    private List<String> emitted() {
        return emissions.stream().flatMap(List::stream).toList();
    }

    private ScopedStageContext<String> ctx(Map<String, Object> options) {
        Map<String, Object> allOptions = new HashMap<>(options);
        allOptions.put("defaultProject", es.getIndexName());
        return new ScopedStageContext<>() {
            public Project project() {return Project.project(es.getIndexName());}

            public Map<String, Object> options() {return allOptions;}

            public boolean isCancelled() {return cancelled;}

            public void emit(List<String> out) {
                emissions.add(out);
                cancelled = cancelAfterFirstEmission;
            }
        };
    }
}
