package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.CancelException;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.model.Statement;
import org.icij.datashare.model.StatementRepository;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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
        assertThat(second.rows()).isEqualTo(2L);
        assertThat(second.written()).isEqualTo(2);
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
                                                                         new PropertiesProvider(), taskView, done -> null).call())
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
        Document document = source("companies.csv", "text/csv", "id,name\n1,ACME\n");
        ExtractionMapping mapping = new ExtractionMapping("m1", "bad/name", null, "companies", "ftm", "docId", null,
                RowSourceOptions.defaults(),
                Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                        Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));
        doReturn(document).when(indexer).get("bad/name", "docId", "docId", CONTENT_FIELDS);
        when(mappings.get("bad/name", "m1")).thenReturn(Optional.of(mapping));
        Task<StructuredEntityExtractionResult> taskView = new Task<>(
                StructuredEntityExtractionTask.class.getName(), User.localUser("jane", List.of("bad/name")),
                Map.of("defaultProject", "bad/name", "mappingId", "m1"));

        assertThrows(IllegalArgumentException.class,
                     () -> new StructuredEntityExtractionTask(indexer, statements, mappings, new PropertiesProvider(),
                                                              taskView, done -> null).call());
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
    public void test_a_cancelled_run_leaves_the_sheet_the_previous_run_wrote() throws Exception {
        source("companies.csv", "text/csv", "id,name\n1,ACME\n2,Globex\n");
        stored(mapping("m1"));
        task("m1").call();
        Map<String, Statement> before = Map.copyOf(statements.stored);
        StructuredEntityExtractionTask task = task("m1");

        task.cancel(false);
        assertThrows(CancelException.class, task::call);

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(statements.stored).isEqualTo(before);
    }

    @Test
    public void test_a_cancel_landing_after_the_read_is_not_reported_as_a_clean_run() throws Exception {
        source("companies.csv", "text/csv", "id,name\n");
        stored(mapping("m1"));
        AtomicReference<StructuredEntityExtractionTask> running = new AtomicReference<>();
        // A source with no data row never runs the row lambda, so this is the only cancellation the
        // task can still observe before it drops and refills the project's entities index.
        InMemoryStatementRepository cancelling = new InMemoryStatementRepository() {
            @Override
            public Replaced replace(String projectId, String runId, String documentId, String sheet,
                                    Stream<Statement> rows) {
                running.get().cancel(false);
                return super.replace(projectId, runId, documentId, sheet, rows);
            }
        };
        running.set(task("m1", cancelling));

        assertThrows(CancelException.class, () -> running.get().call());

        assertThat(indexer.exists(Project.entitiesIndex("prj"))).isFalse();
    }

    @Test
    public void test_a_cancelled_run_over_a_source_with_no_data_row_still_throws() throws Exception {
        source("companies.csv", "text/csv", "id,name\n");
        stored(mapping("m1"));
        StructuredEntityExtractionTask task = task("m1");

        task.cancel(false);
        assertThrows(CancelException.class, task::call);

        assertThat(indexer.exists(Project.entitiesIndex("prj"))).isFalse();
    }

    @Test
    public void test_the_statements_are_stored_under_the_sheet_name_cleaned() throws Exception {
        workbook("Donnees\u00a02");
        stored(mapping("m1"));

        StructuredEntityExtractionResult result = task("m1").call();

        assertThat(result.written()).isEqualTo(1);
        assertThat(statements.stored.values().iterator().next().provenance().sheet()).isEqualTo("Donnees 2");
    }

    private Document source(String filename, String contentType, String content) throws Exception {
        Path file = folder.getRoot().toPath().resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return indexed(file, contentType);
    }

    private void workbook(String sheetName) throws Exception {
        Path file = folder.getRoot().toPath().resolve("companies.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet(sheetName);
            sheet.createRow(0).createCell(0).setCellValue("id");
            sheet.getRow(0).createCell(1).setCellValue("name");
            sheet.createRow(1).createCell(0).setCellValue("1");
            sheet.getRow(1).createCell(1).setCellValue("ACME");
            workbook.write(out);
        }
        indexed(file, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private Document indexed(Path file, String contentType) {
        Document document = DocumentBuilder.createDoc("docId").with(file).ofContentType(contentType)
                                           .with(StandardCharsets.UTF_8).with(Map.of()).build();
        doReturn(document).when(indexer).get("prj", "docId", "docId", CONTENT_FIELDS);
        return document;
    }

    private ExtractionMapping mapping(String id) {
        return new ExtractionMapping(id, "prj", null, "companies", "ftm", "docId", null, RowSourceOptions.defaults(),
                Map.of("c", new ExtractionMapping.EntityMapping("Company", List.of("id"),
                        Map.of("name", new ExtractionMapping.PropertyMapping(List.of("name"), null, null, null, null)))));
    }

    private void stored(ExtractionMapping mapping) {
        when(mappings.get("prj", mapping.id())).thenReturn(Optional.of(mapping));
    }

    private StructuredEntityExtractionTask task(String mappingId) {
        return task(mappingId, statements);
    }

    private StructuredEntityExtractionTask task(String mappingId, StatementRepository store) {
        Task<StructuredEntityExtractionResult> taskView = new Task<>(
                StructuredEntityExtractionTask.class.getName(), User.localUser("jane", List.of("prj")),
                Map.of("defaultProject", "prj", "mappingId", mappingId));
        return new StructuredEntityExtractionTask(indexer, store, mappings, new PropertiesProvider(), taskView,
                                                  done -> null);
    }
}
