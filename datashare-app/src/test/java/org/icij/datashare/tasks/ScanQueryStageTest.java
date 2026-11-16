package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.ScopedStageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PipelineHelper.STAGES_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SEARCH_QUERY_OPT;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.WELSH;
import static org.junit.Assert.assertThrows;

public class ScanQueryStageTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final ElasticsearchIndexer indexer =
            new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    private final ScanQueryStage stage = new ScanQueryStage(indexer);
    private final List<Path> emitted = new ArrayList<>();

    @Test
    public void test_emits_only_the_paths_the_query_selects() throws Exception {
        // neutral ids: DocumentBuilder derives content and path from the id, so a welsh id would match
        // "language:WELSH" through the content field even if the language field were dropped
        indexer.add(es.getIndexName(), DocumentBuilder.createDoc("doc1").with(WELSH).build());
        indexer.add(es.getIndexName(), DocumentBuilder.createDoc("doc2").with(ENGLISH).build());

        StageResult result = run(Map.of(SEARCH_QUERY_OPT, "language:WELSH"));

        assertThat(emitted).containsOnly(Paths.get("/path/to/doc1"));
        assertThat(result).isEqualTo(new StageResult(1, 0, 0, 0));
    }

    @Test
    public void test_emits_the_container_path_once_per_embedded_match() throws Exception {
        // an embedded document carries its container's path: collapsing the duplicates is the DEDUPLICATE stage's job
        indexer.add(es.getIndexName(), DocumentBuilder.createDoc("doc1").with(WELSH).build());
        indexer.add(es.getIndexName(), DocumentBuilder.createDoc("doc2").with(WELSH)
                .with(Paths.get("/path/to/doc1")).withParentId("doc1").withRootId("doc1").build());

        StageResult result = run(Map.of(SEARCH_QUERY_OPT, "language:WELSH"));

        assertThat(emitted).hasSize(2);
        assertThat(emitted).containsOnly(Paths.get("/path/to/doc1"));
        assertThat(result.updated()).isEqualTo(2);
    }

    @Test
    public void test_a_missing_or_blank_query_is_refused_instead_of_scanning_the_whole_index() {
        // an unset variable in a wrapper script must not re-extract the entire corpus
        assertThat(assertThrows(IllegalArgumentException.class, () -> run(Map.of())).getMessage())
                .contains("--searchQuery");
        assertThat(assertThrows(IllegalArgumentException.class, () -> run(Map.of(SEARCH_QUERY_OPT, " "))).getMessage())
                .contains("--searchQuery");
    }

    @Test
    public void test_a_next_stage_draining_no_paths_is_refused() {
        assertThat(assertThrows(IllegalArgumentException.class,
                                () -> run(Map.of(SEARCH_QUERY_OPT, "language:WELSH", STAGES_OPT, "SCANQUERY,NLP")))
                           .getMessage()).contains("NLP");
    }

    private StageResult run(Map<String, Object> extraOptions) {
        Map<String, Object> options = new HashMap<>(Map.of("defaultProject", es.getIndexName(),
                                                           STAGES_OPT, "SCANQUERY,INDEX"));
        options.putAll(extraOptions);
        return stage.process(List.of(), new ScopedStageContext<Path>() {
            public Project project() {return Project.project(es.getIndexName());}

            public Map<String, Object> options() {return options;}

            public boolean isCancelled() {return false;}

            public void emit(List<Path> out) {emitted.addAll(out);}
        });
    }

    @After
    public void tearDown() throws IOException {
        es.removeAll();
    }
}
