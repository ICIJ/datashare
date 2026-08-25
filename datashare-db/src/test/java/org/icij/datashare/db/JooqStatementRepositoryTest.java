package org.icij.datashare.db;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Statement;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.time.DatashareTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.Tables.STATEMENT;

@RunWith(Parameterized.class)
public class JooqStatementRepositoryTest {
    @Rule public DbSetupRule dbRule;
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2020-07-08T12:13:14Z");
    private final JooqStatementRepository repository;

    private static Statement birthDate(String value) {
        return Statement.of("ftm", "entity-1", "Person", "birthDate", value,
                new Statement.Provenance("doc-1", "", 12L, "dob"));
    }

    @Test
    public void test_save_writes_one_row_per_statement() {
        assertThat(repository.save("prj", "run-1", List.of(birthDate("1970-01-01")))).isEqualTo(1);
        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(1);
    }

    @Test
    public void test_save_fills_the_ontology_version_without_the_caller_supplying_it() {
        repository.save("prj", "run-1", List.of(birthDate("1970-01-01")));
        assertThat(dbRule.dsl().select(STATEMENT.MODEL_VERSION).from(STATEMENT).fetchOne().value1())
                .isEqualTo("4.10.2");
    }

    @Test
    public void test_save_stores_the_property_namespaced() {
        repository.save("prj", "run-1", List.of(birthDate("1970-01-01")));
        assertThat(dbRule.dsl().select(STATEMENT.PROPERTY).from(STATEMENT).fetchOne().value1())
                .isEqualTo("ftm:birthDate");
    }

    @Test
    public void test_save_stores_the_provenance() {
        repository.save("prj", "run-1", List.of(birthDate("1970-01-01")));
        var row = dbRule.dsl().selectFrom(STATEMENT).fetchOne();
        assertThat(row.getDocId()).isEqualTo("doc-1");
        assertThat(row.getSheet()).isEqualTo("");
        assertThat(row.getRowNumber()).isEqualTo(12L);
        assertThat(row.getColumnName()).isEqualTo("dob");
        assertThat(row.getRunId()).isEqualTo("run-1");
        assertThat(row.getPrjId()).isEqualTo("prj");
    }

    @Test
    public void test_saving_the_same_data_twice_changes_nothing_except_last_seen_and_run_id() {
        Collection<Statement> statements = List.of(birthDate("1970-01-01"));
        repository.save("prj", "run-1", statements);
        var first = dbRule.dsl().selectFrom(STATEMENT).fetchOne();

        DatashareTime.getInstance().addMilliseconds(60_000);
        assertThat(repository.save("prj", "run-2", statements)).isEqualTo(1);

        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(1);
        var second = dbRule.dsl().selectFrom(STATEMENT).fetchOne();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getFirstSeen()).isEqualTo(first.getFirstSeen());
        assertThat(second.getLastSeen().isAfter(first.getLastSeen())).isTrue();
        assertThat(second.getRunId()).isEqualTo("run-2");
    }

    @Test
    public void test_saving_the_same_data_twice_refreshes_the_model_version() {
        Collection<Statement> statements = List.of(birthDate("1970-01-01"));
        repository.save("prj", "run-1", statements);
        dbRule.dsl().update(STATEMENT).set(STATEMENT.MODEL_VERSION, "stale").execute();

        repository.save("prj", "run-2", statements);

        assertThat(dbRule.dsl().select(STATEMENT.MODEL_VERSION).from(STATEMENT).fetchOne().value1())
                .isEqualTo("4.10.2");
    }

    @Test
    public void test_a_different_value_is_a_different_statement() {
        assertThat(repository.save("prj", "run-1", List.of(birthDate("1970-01-01"), birthDate("1980-02-02")))).isEqualTo(2);
        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(2);
    }

    private static Statement statement(String entityId, String type, String property, String value) {
        return Statement.of("ftm", entityId, type, property, value,
                new Statement.Provenance("doc-1", "", 12L, property));
    }

    @Test
    public void test_entity_regroups_its_statements() {
        repository.save("prj", "run-1", List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-1", "Person", "birthDate", "1815-12-10")));

        ModelEntity entity = repository.entity("prj", "e-1").orElseThrow();
        assertThat(entity.id()).isEqualTo("e-1");
        assertThat(entity.properties().get("name")).containsExactly("Ada");
        assertThat(entity.properties().get("birthDate")).containsExactly("1815-12-10");
    }

    @Test
    public void test_an_entity_keeps_every_one_of_its_types() {
        repository.save("prj", "run-1", List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-1", "LegalEntity", "name", "Ada")));

        assertThat(repository.entity("prj", "e-1").orElseThrow().types()).contains("Person", "LegalEntity");
    }

    @Test
    public void test_a_property_keeps_every_one_of_its_values() {
        repository.save("prj", "run-1", List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-1", "Person", "name", "Ada Lovelace")));

        assertThat(repository.entity("prj", "e-1").orElseThrow().properties().get("name"))
                .containsOnly("Ada", "Ada Lovelace");
    }

    @Test
    public void test_entity_is_empty_when_the_project_holds_no_statement_for_it() {
        repository.save("prj", "run-1", List.of(statement("e-1", "Person", "name", "Ada")));
        assertThat(repository.entity("other", "e-1")).isEqualTo(Optional.empty());
        assertThat(repository.entity("prj", "e-2")).isEqualTo(Optional.empty());
    }

    @Test
    public void test_entities_returns_every_entity_of_the_project_and_no_other() {
        repository.save("prj", "run-1", List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-2", "Person", "name", "Grace")));
        repository.save("other", "run-2", List.of(statement("e-3", "Person", "name", "Alan")));

        try (Stream<ModelEntity> entities = repository.entities("prj")) {
            assertThat(entities.map(ModelEntity::id).toList()).containsOnly("e-1", "e-2");
        }
    }

    @Test
    public void test_entities_does_not_split_an_entity_across_two_results() {
        repository.save("prj", "run-1", List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-2", "Person", "name", "Grace"),
                statement("e-1", "Person", "birthDate", "1815-12-10")));

        try (Stream<ModelEntity> entities = repository.entities("prj")) {
            List<ModelEntity> found = entities.toList();
            assertThat(found.size()).isEqualTo(2);
            assertThat(found.stream().filter(e -> e.id().equals("e-1")).findFirst().orElseThrow()
                    .properties().keySet()).containsOnly("name", "birthDate");
        }
    }

    @Parameterized.Parameters
    public static Collection<Object[]> dataSources() {
        return asList(new Object[][]{
                {DbTestRuleProvider.getSqliteRule()},
                {DbTestRuleProvider.getPostgresRule()}
        });
    }

    public JooqStatementRepositoryTest(DbSetupRule rule) {
        dbRule = rule;
        repository = rule.createStatementRepository();
    }
}
