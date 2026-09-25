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
