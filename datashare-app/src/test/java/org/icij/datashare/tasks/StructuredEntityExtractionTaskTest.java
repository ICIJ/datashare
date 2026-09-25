package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.tabular.RowSourceOptions;
import org.icij.datashare.tabular.StatementBuilder;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tabular.TabularRowReader.CONTENT_FIELDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class StructuredEntityExtractionTaskTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private final ElasticsearchIndexer indexer =
            spy(new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True));
    private final InMemoryStatementRepository statements = new InMemoryStatementRepository();
    private final ExtractionMappingRepository mappings = mock(ExtractionMappingRepository.class);

    @Before
    public void setUp() throws IOException {
        es.delete(Project.entitiesIndex("prj"));
    }

    @After
    public void tearDown() throws IOException {
        es.delete(Project.entitiesIndex("prj"));
    }

    @Test
    public void test_runs_a_mapping_into_statements_and_the_entities_index() throws Exception {
        source("companies.csv", "text/csv", "id,name\n1,ACME\n2,Globex\n");
        stored(mapping("m1"));

        StructuredEntityExtractionResult result = task("m1").call();

        assertThat(result.rows()).isEqualTo(2L);
        assertThat(result.written()).isEqualTo(2);
        assertThat(result.retracted()).isEqualTo(0);
        assertThat(result.indexed()).isEqualTo(2);
        assertThat(result.skipped().get(StatementBuilder.Skip.ENTITY_UNIDENTIFIED)).isEqualTo(0L);
        assertThat(indexer.exists(Project.entitiesIndex("prj"))).isTrue();
    }

    @Test
    public void test_running_it_again_leaves_the_same_statements() throws Exception {
        source("companies.csv", "text/csv", "id,name\n1,ACME\n2,Globex\n");
        stored(mapping("m1"));
        task("m1").call();
        List<String> first = List.copyOf(statements.stored.keySet());

        StructuredEntityExtractionResult second = task("m1").call();

        assertThat(List.copyOf(statements.stored.keySet())).isEqualTo(first);
        assertThat(second.retracted()).isEqualTo(second.written());
    }

    @Test
    public void test_refuses_a_mapping_the_project_does_not_hold() throws Exception {
        source("companies.csv", "text/csv", "id,name\n1,ACME\n");
        when(mappings.get("prj", "missing")).thenReturn(Optional.empty());

        assertThat(assertThrows(IllegalArgumentException.class, () -> task("missing").call()).getMessage())
                .contains("no mapping 'missing'");
    }

    @Test
    public void test_refuses_a_project_the_user_is_not_granted() throws Exception {
        source("companies.csv", "text/csv", "id,name\n1,ACME\n");
        stored(mapping("m1"));
        Task<StructuredEntityExtractionResult> taskView = new Task<>(
                StructuredEntityExtractionTask.class.getName(), User.localUser("bob", List.of("other")),
                Map.of("defaultProject", "prj", "mappingId", "m1"));

        assertThat(assertThrows(IllegalArgumentException.class,
                                () -> new StructuredEntityExtractionTask(indexer, statements, mappings,
                                                                         new PropertiesProvider(), taskView, null).call())
                           .getMessage()).contains("is not granted prj");
    }

    @Test
    public void test_a_correction_supersedes_the_statements_the_previous_mapping_wrote() throws Exception {
        source("companies.csv", "text/csv", "id,name\n1,ACME\n2,Globex\n");
        stored(mapping("m1"));
        task("m1").call();
        source("companies.csv", "text/csv", "id,name\n1,ACME\n");
        stored(mapping("m2"));

        StructuredEntityExtractionResult corrected = task("m2").call();

        assertThat(corrected.retracted()).isEqualTo(2);
        assertThat(corrected.written()).isEqualTo(1);
        assertThat(statements.stored).hasSize(1);
    }

    @Test
    public void test_refuses_a_project_id_before_it_writes_anything() throws Exception {
        source("companies.csv", "text/csv", "id,name\n1,ACME\n");
        ExtractionMapping mapping = new ExtractionMapping("m1", "bad/name", null, "companies", "ftm", "docId",
                RowSourceOptions.defaults(),
                Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                        Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));
        when(mappings.get("bad/name", "m1")).thenReturn(Optional.of(mapping));
        Task<StructuredEntityExtractionResult> taskView = new Task<>(
                StructuredEntityExtractionTask.class.getName(), User.localUser("jane", List.of("bad/name")),
                Map.of("defaultProject", "bad/name", "mappingId", "m1"));

        assertThrows(IllegalArgumentException.class,
                     () -> new StructuredEntityExtractionTask(indexer, statements, mappings, new PropertiesProvider(),
                                                              taskView, null).call());
        assertThat(statements.stored).isEmpty();
    }

    @Test
    public void test_the_task_id_fits_the_run_id_column() {
        Task<StructuredEntityExtractionResult> taskView = new Task<>(
                StructuredEntityExtractionTask.class.getName(), User.local(), Map.of());

        assertThat(taskView.getId().length()).isLessThan(97);
    }

    @Test
    public void test_a_source_with_no_data_row_is_a_clean_run() throws Exception {
        source("companies.csv", "text/csv", "id,name\n");
        stored(mapping("m1"));

        StructuredEntityExtractionResult result = task("m1").call();

        assertThat(result.rows()).isEqualTo(0L);
        assertThat(result.written()).isEqualTo(0);
        assertThat(result.retracted()).isEqualTo(0);
        assertThat(result.indexed()).isEqualTo(0);
    }

    @Test
    public void test_an_unsupported_content_type_names_the_readers_that_exist() throws Exception {
        source("scan.pdf", "application/pdf", "not a table");
        stored(mapping("m1"));

        assertThat(assertThrows(IllegalArgumentException.class, () -> task("m1").call()).getMessage())
                .contains("no reader supports application/pdf");
    }

    @Test
    public void test_a_cancelled_run_throws_rather_than_writing_a_truncated_sheet() throws Exception {
        source("companies.csv", "text/csv", "id,name\n1,ACME\n2,Globex\n");
        stored(mapping("m1"));
        StructuredEntityExtractionTask task = task("m1");

        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, task::call);
        } finally {
            Thread.interrupted();
        }

        assertThat(statements.stored).isEmpty();
        assertThat(indexer.exists(Project.entitiesIndex("prj"))).isFalse();
    }

    private void source(String filename, String contentType, String content) throws Exception {
        Path file = folder.getRoot().toPath().resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Document document = DocumentBuilder.createDoc("docId").with(file).ofContentType(contentType)
                                           .with(StandardCharsets.UTF_8).with(Map.of()).build();
        doReturn(document).when(indexer).get("prj", "docId", "docId", CONTENT_FIELDS);
    }

    private ExtractionMapping mapping(String id) {
        return new ExtractionMapping(id, "prj", null, "companies", "ftm", "docId", RowSourceOptions.defaults(),
                Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                        Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));
    }

    private void stored(ExtractionMapping mapping) {
        when(mappings.get("prj", mapping.id())).thenReturn(Optional.of(mapping));
    }

    private StructuredEntityExtractionTask task(String mappingId) {
        Task<StructuredEntityExtractionResult> taskView = new Task<>(
                StructuredEntityExtractionTask.class.getName(), User.localUser("jane", List.of("prj")),
                Map.of("defaultProject", "prj", "mappingId", mappingId));
        return new StructuredEntityExtractionTask(indexer, statements, mappings, new PropertiesProvider(), taskView,
                                                  null);
    }
}
