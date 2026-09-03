package org.icij.datashare.tabular;

import org.icij.datashare.model.Statement;
import org.junit.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tabular.MappingExecutor.Skip.CELL_MISSING;
import static org.icij.datashare.tabular.MappingExecutor.Skip.CELL_UNREADABLE;
import static org.icij.datashare.tabular.MappingExecutor.Skip.ENTITY_EMPTY;
import static org.icij.datashare.tabular.MappingExecutor.Skip.ENTITY_UNIDENTIFIED;
import static org.junit.Assert.assertThrows;

public class MappingExecutorTest {

    private static ExtractionMapping mapping(Map<String, ExtractionMapping.EntityMapping> entities) {
        return new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm", "doc-1",
                RowSourceOptions.defaults(), entities);
    }

    private static ExtractionMapping.EntityMapping entity(String type, List<String> keys,
                                                          Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new ExtractionMapping.EntityMapping(type, keys, properties);
    }

    private static ExtractionMapping.PropertyMapping column(String name) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, null);
    }

    private static ExtractionMapping.PropertyMapping columns(List<String> names) {
        return new ExtractionMapping.PropertyMapping(names, null, null, null, null);
    }

    private static Row row(Map<String, String> values) {
        return new Row(7L, values);
    }

    private static MappingExecutor person(List<String> keys,
                                          Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new MappingExecutor(mapping(Map.of("member", entity("Person", keys, properties))));
    }

    private static Statement of(List<Statement> statements, String property) {
        return statements.stream().filter(candidate -> candidate.property().equals(property))
                .findFirst().orElseThrow();
    }

    @Test
    public void test_a_column_property_becomes_one_statement_carrying_its_cell() {
        List<Statement> statements = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe")));

        assertThat(statements).hasSize(1);
        Statement statement = statements.get(0);
        assertThat(statement.model()).isEqualTo("ftm");
        assertThat(statement.entityType()).isEqualTo("Person");
        assertThat(statement.property()).isEqualTo("name");
        assertThat(statement.value()).isEqualTo("Jane Doe");
        assertThat(statement.originalValue()).isNull();
        assertThat(statement.provenance().documentId()).isEqualTo("doc-1");
        assertThat(statement.provenance().sheet()).isEqualTo("");
        assertThat(statement.provenance().rowNumber()).isEqualTo(7L);
        assertThat(statement.provenance().column()).isEqualTo("full_name");
    }

    @Test
    public void test_the_order_the_keys_are_declared_in_does_not_change_the_entity_id() {
        Map<String, String> cells = Map.of("passport", "AB123", "country", "FR", "full_name", "Jane Doe");
        String one = person(List.of("passport", "country"), Map.of("name", column("full_name")))
                .statements(row(cells)).get(0).entityId();
        String two = person(List.of("country", "passport"), Map.of("name", column("full_name")))
                .statements(row(cells)).get(0).entityId();

        assertThat(one).isEqualTo(two);
    }

    @Test
    public void test_two_mappings_naming_the_same_key_columns_differently_agree() {
        String one = person(List.of("family", "given"), Map.of("name", column("full_name")))
                .statements(row(Map.of("family", "Dupont", "given", "Pierre", "full_name", "Pierre Dupont")))
                .get(0).entityId();
        String two = person(List.of("first", "last"), Map.of("name", column("full_name")))
                .statements(row(Map.of("first", "Pierre", "last", "Dupont", "full_name", "Pierre Dupont")))
                .get(0).entityId();

        assertThat(one).isEqualTo(two);
    }

    @Test
    public void test_two_key_cells_holding_each_other_s_value_are_one_entity() {
        // The price of agreeing across files: nothing in the id says which column a value came from,
        // so a swapped pair reads as the same pair. A key literal is what tells them apart.
        String one = person(List.of("given", "family"), Map.of("name", column("full_name")))
                .statements(row(Map.of("given", "Jean", "family", "Pierre", "full_name", "Jean Pierre")))
                .get(0).entityId();
        String two = person(List.of("given", "family"), Map.of("name", column("full_name")))
                .statements(row(Map.of("given", "Pierre", "family", "Jean", "full_name", "Pierre Jean")))
                .get(0).entityId();

        assertThat(one).isEqualTo(two);
    }

    @Test
    public void test_a_key_column_declared_twice_identifies_the_same_entity_as_once() {
        String twice = person(List.of("passport", "passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).entityId();
        String once = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).entityId();

        assertThat(twice).isEqualTo(once);
    }

    @Test
    public void test_surrounding_whitespace_in_a_key_does_not_split_the_entity() {
        String one = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", " AB123 ", "full_name", "Jane Doe"))).get(0).entityId();
        String two = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).entityId();

        assertThat(one).isEqualTo(two);
    }

    @Test
    public void test_a_row_whose_key_is_blank_yields_no_statement_and_is_counted() {
        MappingExecutor executor = person(List.of("passport"), Map.of("name", column("full_name")));

        assertThat(executor.statements(row(Map.of("passport", "  ", "full_name", "Jane Doe")))).isEmpty();
        assertThat(executor.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_multi_key_missing_one_value_identifies_nothing() {
        // Hashing the values that are left instead would merge every row missing that column into
        // one entity, and make the row indistinguishable from a row of the same type keyed on the
        // one column that is filled. A counted loss beats a silent merge.
        MappingExecutor executor = person(List.of("passport", "country"), Map.of("name", column("full_name")));

        assertThat(executor.statements(row(Map.of("passport", "", "country", "FR",
                "full_name", "Jane Doe")))).isEmpty();
        assertThat(executor.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_key_holding_only_a_non_breaking_space_is_not_an_identifier() {
        MappingExecutor executor = person(List.of("passport"), Map.of("name", column("full_name")));

        List<Statement> statements = executor.statements(row(Map.of("passport", "\u00A0",
                "full_name", "Jane Doe")));

        assertThat(statements).isEmpty();
        assertThat(executor.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_value_cell_holding_a_nul_does_not_cost_the_run() {
        MappingExecutor executor = person(List.of("passport"),
                Map.of("name", column("full_name"), "email", column("mail")));

        List<Statement> statements = executor.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane\u0000Doe", "mail", "jane@example.org")));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).property()).isEqualTo("email");
        assertThat(executor.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
    }

    @Test
    public void test_a_key_cell_holding_a_nul_yields_no_statement_and_is_counted() {
        MappingExecutor executor = person(List.of("passport"), Map.of("name", column("full_name")));

        assertThat(executor.statements(row(Map.of("passport", "AB\u0000123", "full_name", "Jane Doe")))).isEmpty();
        assertThat(executor.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
        assertThat(executor.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_zero_width_space_in_a_key_does_not_split_the_entity() {
        String one = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123\u200B", "full_name", "Jane Doe"))).get(0).entityId();
        String two = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).entityId();

        assertThat(one).isEqualTo(two);
    }

    @Test
    public void test_a_declared_name_pasted_with_a_non_breaking_space_matches_the_header() {
        List<Statement> statements = person(List.of("passport\u00A0"),
                Map.of("name", column("full_name\u00A0")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe")));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).provenance().column()).isEqualTo("full_name");
    }

    @Test
    public void test_a_row_that_fills_no_property_is_counted_as_empty() {
        MappingExecutor executor = person(List.of("passport"), Map.of("name", column("full_name")));

        assertThat(executor.statements(row(Map.of("passport", "AB123", "full_name", "")))).isEmpty();
        assertThat(executor.skipped().get(ENTITY_EMPTY)).isEqualTo(1L);
    }

    @Test
    public void test_two_aliases_of_one_entity_do_not_repeat_a_statement() {
        List<Statement> statements = new MappingExecutor(mapping(Map.of(
                "buyer", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "seller", entity("Person", List.of("passport"), Map.of("name", column("full_name"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe")));

        assertThat(statements).hasSize(1);
    }

    @Test
    public void test_several_columns_without_a_join_are_several_statements() {
        List<Statement> statements = person(List.of("passport"),
                Map.of("name", columns(List.of("full_name", "maiden_name"))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "maiden_name", "Jane Roe")));

        assertThat(statements).hasSize(2);
        assertThat(statements.stream().map(Statement::value).sorted().toList())
                .isEqualTo(List.of("Jane Doe", "Jane Roe"));
        assertThat(statements.stream().map(statement -> statement.provenance().column()).sorted().toList())
                .isEqualTo(List.of("full_name", "maiden_name"));
    }



    @Test
    public void test_a_column_the_source_does_not_have_fails_the_run() {
        MappingExecutor executor = person(List.of("passport"), Map.of("name", column("fullname")));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> executor.statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))));
        assertThat(thrown.getMessage()).contains("fullname");
    }

    @Test
    public void test_a_row_that_omits_a_column_the_first_row_had_is_counted_not_fatal() {
        MappingExecutor executor = person(List.of("passport"),
                Map.of("name", column("full_name"), "email", column("mail")));

        executor.statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                "mail", "jane@example.org")));
        List<Statement> second = executor.statements(new Row(8L, Map.of("passport", "AB124",
                "full_name", "John Roe")));

        assertThat(second).hasSize(1);
        assertThat(executor.skipped().get(CELL_MISSING)).isEqualTo(1L);
    }

    @Test
    public void test_a_column_the_source_stops_carrying_is_counted_once_not_once_per_row() {
        MappingExecutor executor = person(List.of("passport"),
                Map.of("name", column("full_name"), "email", column("mail")));

        executor.statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                "mail", "jane@example.org")));
        executor.statements(new Row(8L, Map.of("passport", "AB124", "full_name", "John Roe")));
        executor.statements(new Row(9L, Map.of("passport", "AB125", "full_name", "Ann Poe")));

        assertThat(executor.skipped().get(CELL_MISSING)).isEqualTo(1L);
    }

}
