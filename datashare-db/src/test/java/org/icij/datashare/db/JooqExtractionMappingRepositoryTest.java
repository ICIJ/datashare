package org.icij.datashare.db;

import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.InvalidExtractionMapping;
import org.icij.datashare.tabular.RowSourceOptions;
import org.icij.datashare.tabular.UnreadableExtractionMapping;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.Tables.EXTRACTION_MAPPING;
import static org.junit.Assert.assertThrows;

@RunWith(Parameterized.class)
public class JooqExtractionMappingRepositoryTest {
    @Rule public DbSetupRule dbRule;
    private final JooqExtractionMappingRepository repository;

    private static ExtractionMapping mapping(String id, String projectId, String userId, String type) {
        return new ExtractionMapping(id, projectId, userId, "members", "ftm", "doc-1",
                RowSourceOptions.defaults().withDelimiter(';').withCharset(StandardCharsets.UTF_8),
                Map.of("member", new ExtractionMapping.EntityMapping(type, List.of("id"),
                        Map.of("name", new ExtractionMapping.PropertyMapping(List.of("full_name"), null, null, null, null)))));
    }

    @Test
    public void test_save_and_get_round_trips_the_mapping() {
        ExtractionMapping expected = mapping("map-1", "prj", "jdoe", "Person");
        assertThat(repository.save(expected)).isTrue();
        assertThat(repository.get("prj", "map-1").orElseThrow()).isEqualTo(expected);
    }

    @Test
    public void test_the_reader_options_survive_the_round_trip() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        RowSourceOptions options = repository.get("prj", "map-1").orElseThrow().options();
        assertThat(options.delimiter()).isEqualTo(';');
        assertThat(options.charset()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    public void test_a_cli_authored_mapping_has_no_user() {
        repository.save(mapping("map-1", "prj", null, "Person"));
        assertThat(repository.get("prj", "map-1").orElseThrow().userId()).isNull();
    }

    @Test
    public void test_a_mapping_is_immutable_once_saved() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        assertThat(repository.save(mapping("map-1", "prj", "someone-else", "LegalEntity"))).isFalse();
        assertThat(repository.get("prj", "map-1").orElseThrow().userId()).isEqualTo("jdoe");
    }

    @Test
    public void test_an_invalid_mapping_is_refused_rather_than_stored() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> repository.save(mapping("map-1", "prj", "jdoe", "Unicorn")));
        assertThat(thrown.violations.toString()).contains("Unicorn");
        assertThat(repository.get("prj", "map-1").isPresent()).isFalse();
    }

    @Test
    public void test_list_is_project_scoped() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        repository.save(mapping("map-2", "other", "jdoe", "Person"));
        assertThat(repository.list("prj").stream().map(ExtractionMapping::id).toList()).containsOnly("map-1");
    }

    @Test
    public void test_get_is_empty_for_an_unknown_id() {
        assertThat(repository.get("prj", "nope").isPresent()).isFalse();
    }

    @Test
    public void test_get_does_not_see_another_projects_mapping() {
        repository.save(mapping("map-1", "prj-a", "jdoe", "Person"));
        assertThat(repository.get("prj-b", "map-1").isPresent()).isFalse();
    }

    @Test
    public void test_delete_does_not_remove_another_projects_mapping() {
        repository.save(mapping("map-1", "prj-a", "jdoe", "Person"));
        assertThat(repository.delete("prj-b", "map-1")).isFalse();
        assertThat(repository.get("prj-a", "map-1").isPresent()).isTrue();
    }

    @Test
    public void test_two_projects_can_each_hold_a_mapping_under_the_same_id() {
        assertThat(repository.save(mapping("map-1", "prj-a", "jdoe", "Person"))).isTrue();
        assertThat(repository.save(mapping("map-1", "prj-b", "jdoe", "LegalEntity"))).isTrue();
        assertThat(repository.get("prj-a", "map-1").orElseThrow().entities().get("member").type()).isEqualTo("Person");
        assertThat(repository.get("prj-b", "map-1").orElseThrow().entities().get("member").type()).isEqualTo("LegalEntity");
    }

    @Test
    public void test_the_user_id_column_is_written() {
        repository.save(mapping("map-1", "prj", null, "Person"));
        repository.save(mapping("map-2", "prj", "jdoe", "Person"));
        assertThat(dbRule.dsl().select(EXTRACTION_MAPPING.USER_ID).from(EXTRACTION_MAPPING)
                .where(EXTRACTION_MAPPING.ID.eq("map-1")).fetchOne(EXTRACTION_MAPPING.USER_ID)).isNull();
        assertThat(dbRule.dsl().select(EXTRACTION_MAPPING.USER_ID).from(EXTRACTION_MAPPING)
                .where(EXTRACTION_MAPPING.ID.eq("map-2")).fetchOne(EXTRACTION_MAPPING.USER_ID)).isEqualTo("jdoe");
    }

    @Test
    public void test_delete_removes_the_mapping() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        assertThat(repository.delete("prj", "map-1")).isTrue();
        assertThat(repository.get("prj", "map-1").isPresent()).isFalse();
        assertThat(repository.delete("prj", "map-1")).isFalse();
    }

    @Test
    public void test_list_skips_a_row_it_cannot_read_and_returns_the_others() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        repository.save(mapping("map-2", "prj", "jdoe", "Person"));
        poison("map-2", "prj");

        assertThat(repository.list("prj").stream().map(ExtractionMapping::id).toList()).containsOnly("map-1");
    }

    @Test
    public void test_get_on_a_poisoned_row_names_the_failure_rather_than_disguising_it_as_io() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        poison("map-1", "prj");

        UnreadableExtractionMapping thrown = assertThrows(UnreadableExtractionMapping.class,
                () -> repository.get("prj", "map-1"));
        assertThat(thrown.id).isEqualTo("map-1");
    }

    private void poison(String id, String projectId) {
        String definition = dbRule.dsl().select(EXTRACTION_MAPPING.DEFINITION).from(EXTRACTION_MAPPING)
                .where(EXTRACTION_MAPPING.ID.eq(id)).and(EXTRACTION_MAPPING.PRJ_ID.eq(projectId))
                .fetchOne(EXTRACTION_MAPPING.DEFINITION);
        String poisoned = definition.replace("\"model\":\"ftm\"", "\"model\":\"bogus\"");
        dbRule.dsl().update(EXTRACTION_MAPPING).set(EXTRACTION_MAPPING.DEFINITION, poisoned)
                .where(EXTRACTION_MAPPING.ID.eq(id)).and(EXTRACTION_MAPPING.PRJ_ID.eq(projectId)).execute();
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {DbTestRuleProvider.getSqliteRule()},
                {DbTestRuleProvider.getPostgresRule()}
        });
    }

    public JooqExtractionMappingRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createExtractionMappingRepository();
    }
}
