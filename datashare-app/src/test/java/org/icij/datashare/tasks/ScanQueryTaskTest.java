package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.extract.queue.DocumentQueue;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PipelineHelper.STAGES_OPT;
import static org.icij.datashare.PropertiesProvider.REPORT_NAME_OPT;
import static org.icij.datashare.PropertiesProvider.propertiesToMap;
import static org.icij.datashare.cli.DatashareCliOptions.SEARCH_QUERY_OPT;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.WELSH;
import static org.junit.Assert.assertThrows;

/**
 * Covers the one invariant of SCANQUERY: every file its query selects lands in the queue the next
 * stage drains, and nothing lands in the report map that would make INDEX skip it again.
 */
public class ScanQueryTaskTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
        put("defaultProject", es.getIndexName());
        put(STAGES_OPT, "SCANQUERY,INDEX");
        put(REPORT_NAME_OPT, "test:report");
    }});
    private final ElasticsearchIndexer indexer =
            new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
    private final MemoryDocumentCollectionFactory<Path> documentCollectionFactory = new MemoryDocumentCollectionFactory<>();

    @Test
    public void test_query_enqueues_only_the_matching_paths() throws Exception {
        // neutral ids: DocumentBuilder derives content and path from the id, so a welsh id would
        // match "language:WELSH" through the content field even if the language field were dropped
        indexer.add(es.getIndexName(), DocumentBuilder.createDoc("doc1").with(WELSH).build());
        indexer.add(es.getIndexName(), DocumentBuilder.createDoc("doc2").with(ENGLISH).build());

        assertThat(scanQueryTaskSearching("language:WELSH").call()).isEqualTo(1);

        assertThat(indexQueue()).containsOnly(Paths.get("/path/to/doc1"));
    }

    @Test
    public void test_query_leaves_the_report_map_untouched() throws Exception {
        // the report map is what makes INDEX skip a path it already extracted, so filling it here
        // would turn the re-extraction the query asks for into a silent no-op
        indexer.add(es.getIndexName(), DocumentBuilder.createDoc("doc1").with(WELSH).build());

        assertThat(scanQueryTaskSearching("language:WELSH").call()).isEqualTo(1);

        assertThat(documentCollectionFactory.createMap("test:report")).isEmpty();
    }

    @Test
    public void test_query_enqueues_the_container_path_once_per_embedded_match() throws Exception {
        // an embedded document carries its container's path: collapsing the duplicates is the
        // DEDUPLICATE stage's job, so that a chain without it still re-extracts everything it matched
        indexer.add(es.getIndexName(), DocumentBuilder.createDoc("doc1").with(WELSH).build());
        indexer.add(es.getIndexName(), DocumentBuilder.createDoc("doc2").with(WELSH)
                .with(Paths.get("/path/to/doc1")).withParentId("doc1").withRootId("doc1").build());

        assertThat(scanQueryTaskSearching("language:WELSH").call()).isEqualTo(2);

        assertThat(indexQueue().size()).isEqualTo(2);
        assertThat(indexQueue()).containsOnly(Paths.get("/path/to/doc1"));
    }

    @Test
    public void test_a_missing_query_is_refused_instead_of_scanning_the_whole_index() {
        // an unset variable in a wrapper script must not re-extract the entire corpus
        ScanQueryTask task = scanQueryTask(Map.of());

        assertThat(assertThrows(IllegalArgumentException.class, task::call).getMessage()).contains("--searchQuery");
    }

    @Test
    public void test_a_blank_query_is_refused_instead_of_scanning_the_whole_index() {
        ScanQueryTask task = scanQueryTask(Map.of(SEARCH_QUERY_OPT, " "));

        assertThat(assertThrows(IllegalArgumentException.class, task::call).getMessage()).contains("--searchQuery");
    }

    @Test
    public void test_a_next_stage_draining_no_paths_is_refused() {
        ScanQueryTask task = scanQueryTask(Map.of(SEARCH_QUERY_OPT, "language:WELSH", STAGES_OPT, "SCANQUERY,NLP"));

        assertThat(assertThrows(IllegalArgumentException.class, task::call).getMessage()).contains("NLP");
    }

    private ScanQueryTask scanQueryTaskSearching(String searchQuery) {
        return scanQueryTask(Map.of(SEARCH_QUERY_OPT, searchQuery));
    }

    private ScanQueryTask scanQueryTask(Map<String, Object> extraArgs) {
        Map<String, Object> args = propertiesToMap(propertiesProvider.getProperties());
        args.putAll(extraArgs);
        return new ScanQueryTask(documentCollectionFactory, indexer,
                new Task<>(ScanQueryTask.class.getName(), User.nullUser(), args), null);
    }

    private DocumentQueue<Path> indexQueue() {
        return documentCollectionFactory.createQueue(
                new PipelineHelper(propertiesProvider).getQueueNameFor(Stage.INDEX), Path.class);
    }

    @After
    public void tearDown() throws IOException {
        es.removeAll();
    }
}
