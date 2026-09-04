package org.icij.datashare.model;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.elasticsearch.client.Request;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

public class EntitiesIndexRebuilderTest {
    @ClassRule public static ElasticsearchRule es = new ElasticsearchRule();
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider())
            .withRefresh(Refresh.True);
    private final InMemoryStatements statements = new InMemoryStatements();
    private final EntitiesIndexRebuilder rebuilder = new EntitiesIndexRebuilder(indexer, statements);

    @Before
    public void setUp() throws IOException {
        es.delete(Project.entitiesIndex("prj"));
    }

    @After
    public void tearDown() throws IOException {
        es.delete(Project.entitiesIndex("prj"));
    }

    @Test
    public void test_rebuild_creates_the_index_when_it_does_not_exist() throws Exception {
        statements.entities.add(entity("person-1", "Jane Doe"));

        assertThat(rebuilder.rebuild("prj").written()).isEqualTo(1);

        assertThat(indexer.exists(Project.entitiesIndex("prj"))).isTrue();
        assertThat(search("{\"query\":{\"match_all\":{}}}")).contains("person-1");
    }

    @Test
    public void test_the_rebuilt_entity_is_searchable_by_an_exact_property_value() throws Exception {
        statements.entities.add(entity("person-1", "Jane Doe"));
        rebuilder.rebuild("prj");

        assertThat(search("{\"query\":{\"term\":{\"properties.ftm_name\":\"Jane Doe\"}}}")).contains("person-1");
    }

    @Test
    public void test_the_rebuilt_entity_is_searchable_by_a_folded_property_value() throws Exception {
        statements.entities.add(entity("person-2", "Émile Zola"));
        rebuilder.rebuild("prj");

        assertThat(search("{\"query\":{\"match\":{\"properties.ftm_name.text\":\"emile\"}}}")).contains("person-2");
    }

    @Test
    public void test_the_rebuilt_entity_is_searchable_by_type_and_document() throws Exception {
        statements.entities.add(entity("person-1", "Jane Doe"));
        rebuilder.rebuild("prj");

        assertThat(search("{\"query\":{\"term\":{\"entityType\":\"Person\"}}}")).contains("person-1");
        assertThat(search("{\"query\":{\"term\":{\"documentIds\":\"doc-1\"}}}")).contains("person-1");
    }

    // The index is dropped before the stream is consumed, so one group the store refuses to fold
    // must not cost the project its whole index.
    @Test
    public void test_an_entity_that_cannot_be_rebuilt_is_skipped_rather_than_failing_the_rebuild() throws Exception {
        statements.unrebuildable = 1;
        statements.entities.add(entity("person-1", "Jane Doe"));

        assertThat(rebuilder.rebuild("prj").written()).isEqualTo(1);

        assertThat(search("{\"query\":{\"match_all\":{}}}")).contains("person-1");
    }

    // A skip only shows up in a log line, so a caller given a single count cannot tell a complete
    // index from one that is missing entities.
    @Test
    public void test_the_rebuild_counts_the_entities_it_skipped() throws Exception {
        statements.unrebuildable = 2;
        statements.entities.add(entity("person-1", "Jane Doe"));

        EntitiesIndexRebuilder.Rebuilt rebuilt = rebuilder.rebuild("prj");

        assertThat(rebuilt.written()).isEqualTo(1);
        assertThat(rebuilt.skipped()).isEqualTo(2);
    }

    // A group the store cannot fold is bad data and is skipped, but statements grouped wrongly are a
    // fault in the read itself: skipping those would report a short index as a complete rebuild.
    @Test
    public void test_a_grouping_fault_stops_the_rebuild_rather_than_being_skipped() {
        statements.misgrouped = 1;
        statements.entities.add(entity("person-1", "Jane Doe"));

        assertThrows(IllegalArgumentException.class, () -> rebuilder.rebuild("prj"));
    }

    @Test
    public void test_a_second_rebuild_drops_an_entity_that_left_the_store() throws Exception {
        statements.entities.add(entity("person-1", "Jane Doe"));
        statements.entities.add(entity("person-2", "John Doe"));
        rebuilder.rebuild("prj");

        statements.entities.remove(1);
        assertThat(rebuilder.rebuild("prj").written()).isEqualTo(1);

        String all = search("{\"query\":{\"match_all\":{}}}");
        assertThat(all).contains("person-1");
        assertThat(all).doesNotContain("person-2");
    }

    @Test
    public void test_a_date_shaped_value_does_not_poison_a_later_non_date_value_in_the_same_property() throws Exception {
        statements.entities.add(birthDate("person-1", "1980-04-02"));
        statements.entities.add(birthDate("person-2", "circa 1980"));

        rebuilder.rebuild("prj");

        String all = search("{\"query\":{\"match_all\":{}}}");
        assertThat(all).contains("person-1");
        assertThat(all).contains("person-2");
    }

    @Test
    public void test_indexes_more_entities_than_one_chunk() throws Exception {
        IntStream.range(0, 2500).forEach(i -> statements.entities.add(entity("person-" + i, "Name " + i)));

        assertThat(rebuilder.rebuild("prj").written()).isEqualTo(2500);

        assertThat(search("{\"query\":{\"match_all\":{}},\"track_total_hits\":true}"))
                .contains("\"value\":2500,\"relation\":\"eq\"");
    }

    @Test
    public void test_rebuild_fails_when_a_bulk_write_is_rejected() throws Exception {
        statements.entities.add(entity("person-1", "Jane Doe"));
        Indexer rejecting = spy(indexer);
        doReturn(false).when(rejecting).bulkAdd(anyString(), anyList());

        try {
            new EntitiesIndexRebuilder(rejecting, statements).rebuild("prj");
            fail("should have reported the rejected bulk write");
        } catch (IOException e) {
            assertThat(e.getMessage()).contains("bulk add rejected");
        }
    }

    @Test
    public void test_rebuild_refuses_a_null_project_id() {
        try {
            rebuilder.rebuild(null);
            fail("should have refused a null project id");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("null");
        } catch (IOException e) {
            fail("should have refused the id before reaching elasticsearch");
        }
    }

    @Test
    public void test_rebuild_refuses_a_project_id_that_is_not_a_project_name() {
        try {
            rebuilder.rebuild("*");
            fail("should have refused a wildcard project id");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("*");
        } catch (IOException e) {
            fail("should have refused the id before reaching elasticsearch");
        }
    }

    @Test
    public void test_rebuild_repairs_an_index_created_with_the_wrong_mappings() throws Exception {
        Request create = new Request("PUT", "/" + Project.entitiesIndex("prj"));
        create.setJsonEntity("{\"mappings\":{\"properties\":{\"id\":{\"type\":\"integer\"}}}}");
        ((RestClientTransport) es.client._transport()).restClient().performRequest(create);
        statements.entities.add(entity("person-1", "Jane Doe"));

        assertThat(rebuilder.rebuild("prj").written()).isEqualTo(1);

        assertThat(search("{\"query\":{\"term\":{\"properties.ftm_name\":\"Jane Doe\"}}}")).contains("person-1");
    }

    // a "properties.*" default_field would pass this and still blow the 1024-clause limit in production
    @Test
    public void test_the_rebuilt_entity_is_searchable_without_naming_a_field() throws Exception {
        statements.entities.add(entity("person-1", "Jane Doe"));
        rebuilder.rebuild("prj");

        assertThat(search("{\"query\":{\"query_string\":{\"query\":\"jane OR nobody\"}}}")).contains("person-1");
        assertThat(search("{\"query\":{\"query_string\":{\"query\":\"Person\"}}}")).contains("person-1");
        assertThat(search("{\"query\":{\"query_string\":{\"query\":\"doc-1\"}}}")).contains("person-1");
    }

    private String search(String query) throws IOException {
        return indexer.executeRaw("POST", Project.entitiesIndex("prj") + "/_search", query);
    }

    private static ModelEntity bareKeyed(String id, String property, String value) {
        return new ModelEntity("ftm", id, "Person", Set.of("4.10.2"), Set.of("doc-1"),
                Map.of(property, List.of(value)));
    }

    private static ModelEntity entity(String id, String name) {
        return bareKeyed(id, "name", name);
    }

    private static ModelEntity birthDate(String id, String value) {
        return bareKeyed(id, "birthDate", value);
    }

    private static class InMemoryStatements implements StatementRepository {
        private final List<ModelEntity> entities = new ArrayList<>();
        private int unrebuildable;
        private int misgrouped;

        @Override
        public int save(String projectId, String runId, Stream<Statement> statements) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> R entities(String projectId, Function<Stream<ModelEntity>, R> consumer) {
            Stream<ModelEntity> refused = IntStream.range(0, unrebuildable).mapToObj(group -> {
                throw new UnrebuildableEntity(List.of("Company", "Person"));
            });
            Stream<ModelEntity> faulty = Stream.of("misgrouped").limit(misgrouped).map(group -> {
                throw new IllegalArgumentException("statements belong to 2 entities: [e-1, e-2]");
            });
            return consumer.apply(Stream.concat(Stream.concat(refused, faulty), entities.stream()));
        }

        @Override
        public Optional<ModelEntity> entity(String projectId, String entityId) {
            return entities.stream().filter(e -> e.id().equals(entityId)).findFirst();
        }
    }
}
