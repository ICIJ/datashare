package org.icij.datashare.db;

import org.icij.datashare.tabular.ExtractionMapping;
import org.icij.datashare.tabular.InvalidExtractionMapping;
import org.icij.datashare.tabular.RowSourceOptions;
import org.icij.datashare.test.DatashareTimeRule;
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
import static org.junit.Assert.assertThrows;

@RunWith(Parameterized.class)
public class JooqExtractionMappingRepositoryTest {
    @Rule public DbSetupRule dbRule;
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2020-07-08T12:13:14Z");
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
        assertThat(repository.get("map-1").orElseThrow()).isEqualTo(expected);
    }

    @Test
    public void test_the_reader_options_survive_the_round_trip() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        RowSourceOptions options = repository.get("map-1").orElseThrow().options();
        assertThat(options.delimiter()).isEqualTo(';');
        assertThat(options.charset()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    public void test_a_cli_authored_mapping_has_no_user() {
        repository.save(mapping("map-1", "prj", null, "Person"));
        assertThat(repository.get("map-1").orElseThrow().userId()).isNull();
    }

    @Test
    public void test_a_mapping_is_immutable_once_saved() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        assertThat(repository.save(mapping("map-1", "prj", "someone-else", "LegalEntity"))).isFalse();
        assertThat(repository.get("map-1").orElseThrow().userId()).isEqualTo("jdoe");
    }

    @Test
    public void test_an_invalid_mapping_is_refused_rather_than_stored() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> repository.save(mapping("map-1", "prj", "jdoe", "Unicorn")));
        assertThat(thrown.violations.toString()).contains("Unicorn");
        assertThat(repository.get("map-1").isPresent()).isFalse();
    }

    @Test
    public void test_list_is_project_scoped() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        repository.save(mapping("map-2", "other", "jdoe", "Person"));
        assertThat(repository.list("prj").stream().map(ExtractionMapping::id).toList()).containsOnly("map-1");
    }

    @Test
    public void test_get_is_empty_for_an_unknown_id() {
        assertThat(repository.get("nope").isPresent()).isFalse();
    }

    @Test
    public void test_delete_removes_the_mapping() {
        repository.save(mapping("map-1", "prj", "jdoe", "Person"));
        assertThat(repository.delete("map-1")).isTrue();
        assertThat(repository.get("map-1").isPresent()).isFalse();
        assertThat(repository.delete("map-1")).isFalse();
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
