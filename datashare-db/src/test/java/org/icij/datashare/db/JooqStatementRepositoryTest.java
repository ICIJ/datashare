package org.icij.datashare.db;

import com.zaxxer.hikari.HikariDataSource;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Statement;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.time.DatashareTime;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.Tables.STATEMENT;
import static org.junit.Assert.assertThrows;

@RunWith(Parameterized.class)
public class JooqStatementRepositoryTest {
    @Rule public DbSetupRule dbRule;
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2020-07-08T12:13:14Z");
    private final JooqStatementRepository repository;

    private static Statement statement(String entityId, String type, String property, String value) {
        return Statement.of("ftm", entityId, type, property, value,
                new Statement.Provenance("doc-1", "", 12L, property));
    }

    @Test
    public void test_save_writes_the_original_value_when_a_format_changed_it() {
        repository.save("prj", "run-1", Stream.of(statement("entity-1", "Person", "birthDate", "1970-01-01")
                .withOriginalValue("01/01/1970")));

        assertThat(dbRule.dsl().select(STATEMENT.ORIGINAL_VALUE).from(STATEMENT).fetchOne().value1())
                .isEqualTo("01/01/1970");
    }

    @Test
    public void test_a_statement_with_an_original_value_still_rebuilds_its_entity() {
        repository.save("prj", "run-1", Stream.of(statement("entity-1", "Person", "birthDate", "1970-01-01")
                .withOriginalValue("01/01/1970")));

        assertThat(repository.entity("prj", "entity-1").orElseThrow().properties().get("birthDate"))
                .containsExactly("1970-01-01");
    }

    @Test
    public void test_a_resave_refreshes_the_original_value() {
        Statement fact = statement("entity-1", "Person", "birthDate", "1970-01-01");

        repository.save("prj", "run-1", Stream.of(fact.withOriginalValue("01/01/1970")));
        repository.save("prj", "run-2", Stream.of(fact.withOriginalValue("1970/01/01")));

        assertThat(dbRule.dsl().select(STATEMENT.ORIGINAL_VALUE).from(STATEMENT).fetchOne().value1())
                .isEqualTo("1970/01/01");
    }

    @Test
    public void test_save_binds_original_value_when_the_first_row_of_a_batch_has_none() {
        repository.save("prj", "run-1", Stream.of(
                statement("entity-1", "Person", "name", "Jane Doe"),
                statement("entity-2", "Person", "birthDate", "1970-01-01").withOriginalValue("01/01/1970")));

        assertThat(dbRule.dsl().select(STATEMENT.ORIGINAL_VALUE).from(STATEMENT)
                .where(STATEMENT.ENTITY_ID.eq("entity-2")).fetchOne().value1())
                .isEqualTo("01/01/1970");
        assertThat(dbRule.dsl().select(STATEMENT.ORIGINAL_VALUE).from(STATEMENT)
                .where(STATEMENT.ENTITY_ID.eq("entity-1")).fetchOne().value1()).isNull();
    }

    @Test
    public void test_save_writes_one_row_per_statement() {
        List<Statement> statements = List.of(statement("entity-1", "Person", "birthDate", "1970-01-01"));
        assertThat(repository.save("prj", "run-1", statements.stream())).isEqualTo(1);
        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(1);
    }

    @Test
    public void test_save_fills_the_ontology_version_without_the_caller_supplying_it() {
        repository.save("prj", "run-1", Stream.of(statement("entity-1", "Person", "birthDate", "1970-01-01")));
        assertThat(dbRule.dsl().select(STATEMENT.MODEL_VERSION).from(STATEMENT).fetchOne().value1())
                .isEqualTo("4.10.2");
    }

    @Test
    public void test_save_stores_the_property_namespaced() {
        repository.save("prj", "run-1", Stream.of(statement("entity-1", "Person", "birthDate", "1970-01-01")));
        assertThat(dbRule.dsl().select(STATEMENT.PROPERTY).from(STATEMENT).fetchOne().value1())
                .isEqualTo("ftm:birthDate");
    }

    @Test
    public void test_save_stores_the_provenance() {
        repository.save("prj", "run-1", Stream.of(statement("entity-1", "Person", "birthDate", "1970-01-01")));
        var row = dbRule.dsl().selectFrom(STATEMENT).fetchOne();
        assertThat(row.getDocId()).isEqualTo("doc-1");
        assertThat(row.getSheet()).isEqualTo("");
        assertThat(row.getRowNumber()).isEqualTo(12L);
        assertThat(row.getColumnName()).isEqualTo("birthDate");
        assertThat(row.getRunId()).isEqualTo("run-1");
        assertThat(row.getPrjId()).isEqualTo("prj");
    }




    @Test
    public void test_a_different_value_is_a_different_statement() {
        List<Statement> statements = List.of(
                statement("entity-1", "Person", "birthDate", "1970-01-01"),
                statement("entity-1", "Person", "birthDate", "1980-02-02"));
        assertThat(repository.save("prj", "run-1", statements.stream())).isEqualTo(2);
        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(2);
    }

    @Test
    public void test_the_same_statement_saved_under_two_projects_keeps_both_rows() {
        List<Statement> statements = List.of(statement("entity-1", "Person", "birthDate", "1970-01-01"));
        repository.save("prj-a", "run-1", statements.stream());
        repository.save("prj-b", "run-2", statements.stream());

        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(2);
        assertThat(repository.entity("prj-a", "entity-1")).isNotEqualTo(Optional.empty());
        assertThat(repository.entity("prj-b", "entity-1")).isNotEqualTo(Optional.empty());
        assertThat(dbRule.dsl().select(STATEMENT.RUN_ID).from(STATEMENT)
                .where(STATEMENT.PRJ_ID.eq("prj-a")).fetchOne().value1()).isEqualTo("run-1");
        assertThat(dbRule.dsl().select(STATEMENT.RUN_ID).from(STATEMENT)
                .where(STATEMENT.PRJ_ID.eq("prj-b")).fetchOne().value1()).isEqualTo("run-2");
    }



    @Test
    public void test_saving_the_same_data_twice_keeps_one_row_and_dates_the_new_observation() {
        List<Statement> statements = List.of(statement("entity-1", "Person", "birthDate", "1970-01-01"));
        repository.save("prj", "run-1", statements.stream());
        var first = dbRule.dsl().selectFrom(STATEMENT).fetchOne();

        DatashareTime.getInstance().addMilliseconds(60_000);
        assertThat(repository.save("prj", "run-2", statements.stream())).isEqualTo(1);

        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(1);
        var second = dbRule.dsl().selectFrom(STATEMENT).fetchOne();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getFirstSeen()).isEqualTo(first.getFirstSeen());
        assertThat(second.getLastSeen()).isNotEqualTo(first.getLastSeen());
        assertThat(second.getRunId()).isEqualTo("run-2");
    }

    @Test
    public void test_an_entity_keeps_every_one_of_its_types() {
        repository.save("prj", "run-1", Stream.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-1", "LegalEntity", "name", "Ada")));

        assertThat(repository.entity("prj", "e-1").orElseThrow().types()).contains("Person", "LegalEntity");
    }

    @Test
    public void test_entity_regroups_its_statements() {
        repository.save("prj", "run-1", Stream.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-1", "Person", "birthDate", "1815-12-10")));

        ModelEntity entity = repository.entity("prj", "e-1").orElseThrow();
        assertThat(entity.id()).isEqualTo("e-1");
        assertThat(entity.properties().get("name")).containsExactly("Ada");
        assertThat(entity.properties().get("birthDate")).containsExactly("1815-12-10");
    }


    @Test
    public void test_a_property_keeps_every_one_of_its_values() {
        repository.save("prj", "run-1", Stream.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-1", "Person", "name", "Ada Lovelace")));

        assertThat(repository.entity("prj", "e-1").orElseThrow().properties().get("name"))
                .containsOnly("Ada", "Ada Lovelace");
    }

    @Test
    public void test_entity_is_empty_when_the_project_holds_no_statement_for_it() {
        repository.save("prj", "run-1", Stream.of(statement("e-1", "Person", "name", "Ada")));
        assertThat(repository.entity("other", "e-1")).isEqualTo(Optional.empty());
        assertThat(repository.entity("prj", "e-2")).isEqualTo(Optional.empty());
    }

    @Test
    public void test_entities_returns_every_entity_of_the_project_and_no_other() {
        repository.save("prj", "run-1", Stream.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-2", "Person", "name", "Grace")));
        repository.save("other", "run-2", Stream.of(statement("e-3", "Person", "name", "Alan")));

        List<ModelEntity> found = repository.entities("prj", Stream::toList);
        assertThat(found.stream().map(ModelEntity::id).toList()).containsOnly("e-1", "e-2");
    }

    @Test
    public void test_entities_does_not_split_an_entity_across_two_results() {
        repository.save("prj", "run-1", Stream.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-2", "Person", "name", "Grace"),
                statement("e-1", "Person", "birthDate", "1815-12-10")));

        List<ModelEntity> found = repository.entities("prj", Stream::toList);
        assertThat(found.size()).isEqualTo(2);
        assertThat(found.stream().filter(e -> e.id().equals("e-1")).findFirst().orElseThrow()
                .properties().keySet()).containsOnly("name", "birthDate");
    }

    @Test
    public void test_entities_produces_bare_property_keys_not_namespaced() {
        repository.save("prj", "run-1", Stream.of(statement("e-1", "Person", "name", "Ada")));

        List<ModelEntity> found = repository.entities("prj", Stream::toList);

        assertThat(found.get(0).properties().keySet()).containsOnly("name");
    }

    @Test
    public void test_property_values_come_back_in_a_stable_order_across_a_resave() {
        List<Statement> statements = List.of(
                statement("e-1", "Person", "tag", "b"),
                statement("e-1", "Person", "tag", "a"),
                statement("e-1", "Person", "tag", "c"));

        repository.save("prj", "run-1", statements.stream());
        assertThat(repository.entity("prj", "e-1").orElseThrow().properties().get("tag"))
                .isEqualTo(List.of("a", "b", "c"));

        repository.save("prj", "run-2", statements.stream());
        assertThat(repository.entity("prj", "e-1").orElseThrow().properties().get("tag"))
                .isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    public void test_property_values_are_ordered_the_same_way_on_every_dialect() {
        repository.save("prj", "run-1", Stream.of(
                statement("e-1", "Person", "tag", "b-c"),
                statement("e-1", "Person", "tag", "Zoe"),
                statement("e-1", "Person", "tag", "abc"),
                statement("e-1", "Person", "tag", "Abc")));

        assertThat(repository.entity("prj", "e-1").orElseThrow().properties().get("tag"))
                .isEqualTo(List.of("Abc", "Zoe", "abc", "b-c"));
    }

    @Test
    public void test_entities_splits_an_entity_id_shared_by_two_models_into_two_entities() {
        LocalDateTime now = LocalDateTime.now();
        insertRawStatement("id-1", "ftm", "shared", "Person", "ftm:name", "Ada", now);
        insertRawStatement("id-2", "other", "shared", "Thing", "other:name", "Ada", now);

        List<ModelEntity> entities = repository.entities("prj", Stream::toList);

        assertThat(entities.size()).isEqualTo(2);
        assertThat(entities.stream().allMatch(entity -> entity.id().equals("shared"))).isTrue();
        assertThat(entities.stream().map(ModelEntity::model).toList()).containsOnly("ftm", "other");
    }

    @Test
    public void test_entity_serves_the_first_model_of_an_entity_id_shared_by_two() {
        LocalDateTime now = LocalDateTime.now();
        insertRawStatement("id-1", "ftm", "shared", "Person", "ftm:name", "Ada", now);
        insertRawStatement("id-2", "other", "shared", "Thing", "other:name", "Grace", now);

        ModelEntity entity = repository.entity("prj", "shared").orElseThrow();
        assertThat(entity.id()).isEqualTo("shared");
        assertThat(entity.types()).containsOnly("Person");
        assertThat(entity.properties().get("name")).containsOnly("Ada");
    }

    @Test
    public void test_a_property_that_is_not_namespaced_under_its_model_names_the_row_it_came_from() {
        insertRawStatement("id-1", "ftm", "e-1", "Person", "nm", "Ada", LocalDateTime.now());

        DataAccessException thrown = assertThrows(DataAccessException.class,
                () -> repository.entity("prj", "e-1"));
        assertThat(thrown.getMessage()).contains("id-1").contains("nm").contains("ftm");
    }

    private void insertRawStatement(String id, String model, String entityId, String entityType,
                                     String property, String value, LocalDateTime now) {
        dbRule.dsl().insertInto(STATEMENT)
                .set(STATEMENT.ID, id)
                .set(STATEMENT.PRJ_ID, "prj")
                .set(STATEMENT.RUN_ID, "run-1")
                .set(STATEMENT.MODEL, model)
                .set(STATEMENT.MODEL_VERSION, "1.0")
                .set(STATEMENT.ENTITY_ID, entityId)
                .set(STATEMENT.ENTITY_TYPE, entityType)
                .set(STATEMENT.PROPERTY, property)
                .set(STATEMENT.VALUE, value)
                .set(STATEMENT.DOC_ID, "doc-1")
                .set(STATEMENT.SHEET, "")
                .set(STATEMENT.ROW_NUMBER, 1L)
                .set(STATEMENT.COLUMN_NAME, "name")
                .set(STATEMENT.FIRST_SEEN, now)
                .set(STATEMENT.LAST_SEEN, now)
                .execute();
    }

    @Test
    public void test_an_entity_carries_the_model_version_of_its_statements() {
        repository.save("prj", "run-1", Stream.of(
                Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                        new Statement.Provenance("doc-1", "Sheet1", 12, "full_name"))));

        ModelEntity entity = repository.entity("prj", "person-1").orElseThrow();

        assertThat(entity.model()).isEqualTo("ftm");
        assertThat(entity.modelVersions()).containsOnly("4.10.2");
    }

    @Test
    public void test_an_entity_carries_the_documents_its_statements_came_from() {
        repository.save("prj", "run-1", Stream.of(
                Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                        new Statement.Provenance("doc-1", "Sheet1", 12, "full_name")),
                Statement.of("ftm", "person-1", "Person", "birthDate", "1980-04-02",
                        new Statement.Provenance("doc-2", "Sheet1", 3, "dob"))));

        ModelEntity entity = repository.entity("prj", "person-1").orElseThrow();

        assertThat(entity.documentIds()).containsOnly("doc-1", "doc-2");
    }

    @Test
    public void test_the_streamed_entities_carry_the_model_version_too() {
        repository.save("prj", "run-1", Stream.of(
                Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                        new Statement.Provenance("doc-1", "Sheet1", 12, "full_name"))));

        List<ModelEntity> entities = repository.entities("prj", Stream::toList);

        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).modelVersions()).containsOnly("4.10.2");
        assertThat(entities.get(0).documentIds()).containsOnly("doc-1");
    }

    @Test
    public void test_entities_gives_the_connection_back_to_the_pool_on_a_short_circuit() {
        repository.save("prj", "run-1", Stream.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-2", "Person", "name", "Grace")));

        assertThat(repository.entities("prj", Stream::findFirst).isPresent()).isTrue();
        assertThat(activeConnections()).isEqualTo(0);
    }

    @Test
    public void test_save_crosses_the_chunk_boundary() {
        SQLDialect dialect = RepositoryFactoryImpl.guessSqlDialectFrom(dbRule.dataSourceUrl);
        JooqStatementRepository chunked = new JooqStatementRepository(dbRule.dataSource, dialect, 2);
        List<Statement> statements = List.of(
                statement("e-1", "Person", "name", "Ada"),
                statement("e-2", "Person", "name", "Grace"),
                statement("e-3", "Person", "name", "Alan"));

        assertThat(chunked.save("prj", "run-1", statements.stream())).isEqualTo(3);
        assertThat(dbRule.dsl().fetchCount(STATEMENT)).isEqualTo(3);
    }

    // Only pgjdbc opens a server-side cursor, and only outside autocommit. SQLite ignores fetchSize
    // and would hold a shared lock on the whole file for as long as the consumer runs, so it must be
    // left in autocommit: this pins both halves of that split.
    @Test
    public void test_entities_streams_out_of_autocommit_on_postgres_only() {
        repository.save("prj", "run-1", Stream.of(statement("e-1", "Person", "name", "Ada")));
        SQLDialect dialect = RepositoryFactoryImpl.guessSqlDialectFrom(dbRule.dataSourceUrl);

        AtomicBoolean autoCommit = new AtomicBoolean(true);
        AtomicInteger fetchSize = new AtomicInteger(-1);
        JooqStatementRepository capturingRepository = new JooqStatementRepository(
                capturingDataSource(dbRule.dataSource, autoCommit, fetchSize), dialect);

        assertThat(capturingRepository.entities("prj", Stream::findFirst).isPresent()).isTrue();
        assertThat(autoCommit.get()).isEqualTo(dialect != SQLDialect.POSTGRES);
        assertThat(fetchSize.get()).isEqualTo(dialect == SQLDialect.POSTGRES ? 1_000 : -1);
    }

    @Test
    public void test_an_entity_names_the_model_it_was_rebuilt_from() {
        repository.save("prj", "run-1", Stream.of(statement("e-1", "Person", "name", "Ada")));

        assertThat(repository.entity("prj", "e-1").orElseThrow().model()).isEqualTo("ftm");
        List<String> models = repository.entities("prj", entities -> entities.map(ModelEntity::model).toList());
        assertThat(models).containsOnly("ftm");
    }

    private int activeConnections() {
        return ((HikariDataSource) dbRule.dataSource).getHikariPoolMXBean().getActiveConnections();
    }

    // No mocking framework is wired into this module: a JDK dynamic proxy delegating to the real
    // JDBC objects lets the test observe setAutoCommit/setFetchSize calls without faking the whole
    // driver's query behaviour.
    private static DataSource capturingDataSource(DataSource real, AtomicBoolean autoCommit, AtomicInteger fetchSize) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invokeReal(real, method, args);
                    return "getConnection".equals(method.getName()) && result instanceof Connection connection
                            ? capturingConnection(connection, autoCommit, fetchSize) : result;
                });
    }

    private static Connection capturingConnection(Connection real, AtomicBoolean autoCommit, AtomicInteger fetchSize) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("setAutoCommit".equals(method.getName())) {
                        autoCommit.set((Boolean) args[0]);
                    }
                    Object result = invokeReal(real, method, args);
                    return result instanceof PreparedStatement statement
                            ? capturingStatement(statement, fetchSize) : result;
                });
    }

    private static PreparedStatement capturingStatement(PreparedStatement real, AtomicInteger fetchSize) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("setFetchSize".equals(method.getName())) {
                        fetchSize.set((Integer) args[0]);
                    }
                    return invokeReal(real, method, args);
                });
    }

    private static Object invokeReal(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
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
