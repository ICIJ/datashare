package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.model.Statement;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.ExtractionMappingReader;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.tabular.ExtractionMappingServiceImpl;
import org.icij.datashare.tabular.JsonRowSource;
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
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toSet;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tabular.TabularRowReader.CONTENT_FIELDS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class FtmDumpExtractionTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private final ElasticsearchIndexer indexer =
            spy(new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True));
    private final InMemoryStatementRepository statements = new InMemoryStatementRepository();
    private final ExtractionMappingRepository mappings = mock(ExtractionMappingRepository.class);
    private final ExtractionMappingServiceImpl service = new ExtractionMappingServiceImpl(mappings, null);

    @Before
    public void setUp() throws IOException {
        es.delete(Project.entitiesIndex("prj"));
    }

    @After
    public void tearDown() throws IOException {
        es.delete(Project.entitiesIndex("prj"));
    }

    private static final int PEOPLE = 1_000;
    private static final int COMPANIES = 500;

    /** People, companies and the ownerships between them, in the shape FtmJsonExporter writes: every
     *  property an array, every line free to carry a different schema, and an edge naming its
     *  endpoints by the ids the file gave them. Every other ownership points at a person, the rest at
     *  a company, so no single schema can be guessed for the 'owner' column. */
    private static String dump() {
        StringBuilder dump = new StringBuilder();
        for (int index = 0; index < PEOPLE; index++) {
            dump.append("{\"id\":\"p%d\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Person %d\"],\"firstName\":[\"First %d\"]}}\n"
                                .formatted(index, index, index));
        }
        for (int index = 0; index < COMPANIES; index++) {
            dump.append("{\"id\":\"c%d\",\"schema\":\"Company\",\"properties\":{\"name\":[\"Company %d\",\"Company %d SA\"],\"registrationNumber\":[\"R%d\"]}}\n"
                                .formatted(index, index, index, index));
        }
        for (int index = 0; index < COMPANIES; index++) {
            String owner = index % 2 == 0 ? "p" + index : "c" + index;
            dump.append("{\"id\":\"o%d\",\"schema\":\"Ownership\",\"properties\":{\"owner\":[\"%s\"],\"asset\":[\"c%d\"],\"percentage\":[]}}\n"
                                .formatted(index, owner, index));
        }
        return dump.toString();
    }

    private static final String MAPPING = """
            {"id":"dump","name":"FtM dump","model":"ftm","documentId":"%s",
             "entities":{
               "person":{"type":"Person","keys":[],"idColumn":"id","filters":{"schema":"Person"},
                 "properties":{"name":{"columns":["properties.name"]},
                               "firstName":{"columns":["properties.firstName"]}}},
               "company":{"type":"Company","keys":[],"idColumn":"id","filters":{"schema":"Company"},
                 "properties":{"name":{"columns":["properties.name"]},
                               "registrationNumber":{"columns":["properties.registrationNumber"]}}},
               "ownership":{"type":"Ownership","keys":[],"idColumn":"id","filters":{"schema":"Ownership"},
                 "properties":{"owner":{"columns":["properties.owner"]},
                               "asset":{"columns":["properties.asset"]},
                               "percentage":{"columns":["properties.percentage"]}}}}}
            """;

    @Test
    public void test_a_mixed_ftm_dump_builds_its_entities_and_its_edges() throws Exception {
        StructuredEntityExtractionResult result = runDump();

        assertThat(result.rows()).isEqualTo(PEOPLE + COMPANIES * 2L);
        assertThat(result.indexed()).isEqualTo(PEOPLE + COMPANIES * 2);
    }

    @Test
    public void test_every_edge_resolves_to_an_indexed_entity_whatever_its_schema() throws Exception {
        runDump();

        Set<String> entityIds = statements.stored.values().stream().map(Statement::entityId).collect(toSet());
        List<Statement> endpoints = statements.stored.values().stream()
                                                .filter(statement -> statement.entityId().startsWith("o"))
                                                .filter(statement -> statement.property().equals("owner") ||
                                                                     statement.property().equals("asset")).toList();

        assertThat(endpoints).hasSize(COMPANIES * 2);
        assertThat(endpoints.stream().map(Statement::value).allMatch(entityIds::contains)).isTrue();
        assertThat(endpoints.stream().map(Statement::value).anyMatch(id -> id.startsWith("p"))).isTrue();
        assertThat(endpoints.stream().map(Statement::value).anyMatch(id -> id.startsWith("c"))).isTrue();
    }

    @Test
    public void test_a_multi_valued_property_becomes_several_statements() throws Exception {
        runDump();

        List<String> names = statements.stored.values().stream()
                                       .filter(statement -> statement.entityId().equals("c0"))
                                       .filter(statement -> statement.property().equals("name"))
                                       .map(Statement::value).sorted().toList();

        assertThat(names).isEqualTo(List.of("Company 0", "Company 0 SA"));
    }

    private StructuredEntityExtractionResult runDump() throws Exception {
        source("dump.jsonl", JsonRowSource.NDJSON_CONTENT_TYPE, dump());
        stored(ExtractionMappingReader.read(ExtractionMappingReader.parse(MAPPING.formatted("docId").getBytes(UTF_8)),
                                            "prj", null));
        return task("dump").call();
    }

    private Document source(String filename, String contentType, String content) throws Exception {
        Path file = folder.getRoot().toPath().resolve(filename);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return indexed(file, contentType);
    }

    private Document indexed(Path file, String contentType) {
        Document document = DocumentBuilder.createDoc("docId").with(file).ofContentType(contentType)
                                           .with(StandardCharsets.UTF_8).with(Map.of()).build();
        doReturn(document).when(indexer).get("prj", "docId", "docId", CONTENT_FIELDS);
        return document;
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
        return new StructuredEntityExtractionTask(indexer, store, service, new PropertiesProvider(), taskView,
                                                  done -> null);
    }
}
