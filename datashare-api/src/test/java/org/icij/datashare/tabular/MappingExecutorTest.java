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

    private static ExtractionMapping.EntityMapping keyed(String type, String keyLiteral, List<String> keys,
                                                         Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new ExtractionMapping.EntityMapping(type, keyLiteral, keys, properties);
    }

    private static ExtractionMapping.PropertyMapping column(String name) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, null);
    }

    private static ExtractionMapping.PropertyMapping columns(List<String> names) {
        return new ExtractionMapping.PropertyMapping(names, null, null, null, null);
    }

    private static ExtractionMapping.PropertyMapping literal(String value) {
        return new ExtractionMapping.PropertyMapping(List.of(), null, value, null, null);
    }

    private static ExtractionMapping.PropertyMapping reference(String alias) {
        return new ExtractionMapping.PropertyMapping(List.of(), null, null, alias, null);
    }

    private static ExtractionMapping.PropertyMapping joined(List<String> columns, String separator) {
        return new ExtractionMapping.PropertyMapping(columns, separator, null, null, null);
    }

    private static ExtractionMapping.PropertyMapping formatted(String name, String pattern) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, pattern);
    }

    private static Row row(Map<String, String> values) {
        return new Row(7L, values);
    }

    private static MappingExecutor person(List<String> keys,
                                          Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new MappingExecutor(mapping(Map.of("member", entity("Person", keys, properties))));
    }

    private static MappingExecutor employment() {
        return new MappingExecutor(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))),
                "job", entity("Employment", List.of("passport"), Map.of(
                        "employee", reference("member"), "employer", reference("employer"))))));
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
    public void test_two_aliases_of_one_type_keyed_alike_are_one_entity_without_a_key_literal() {
        List<Statement> statements = new MappingExecutor(mapping(Map.of(
                "supplier", entity("Company", List.of("supplier_ref"), Map.of("name", column("supplier_name"))),
                "customer", entity("Company", List.of("customer_ref"), Map.of("name", column("customer_name"))))))
                .statements(row(Map.of("supplier_ref", "42", "customer_ref", "42",
                        "supplier_name", "Acme", "customer_name", "Globex")));

        assertThat(statements.get(0).entityId()).isEqualTo(statements.get(1).entityId());
    }

    @Test
    public void test_a_key_literal_tells_two_aliases_of_one_type_keyed_alike_apart() {
        List<Statement> statements = new MappingExecutor(mapping(Map.of(
                "supplier", keyed("Company", "supplier", List.of("supplier_ref"),
                        Map.of("name", column("supplier_name"))),
                "customer", keyed("Company", "customer", List.of("customer_ref"),
                        Map.of("name", column("customer_name"))))))
                .statements(row(Map.of("supplier_ref", "42", "customer_ref", "42",
                        "supplier_name", "Acme", "customer_name", "Globex")));

        assertThat(statements.get(0).entityId()).isNotEqualTo(statements.get(1).entityId());
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
    public void test_the_entity_id_recipe_is_pinned_to_a_literal_hash() {
        // Changing this value orphans every statement already stored under the id it replaces.
        String expected = "ff7fe1f88ad6b4c43b681d67aa06aa0de23f2144fe88561aab83b29c419eb9ade49223d296e8c425a54e3fb42b98e28a";

        String actual = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).entityId();

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void test_an_entity_without_keys_is_one_record_per_row() {
        MappingExecutor executor = person(List.of(), Map.of("name", column("full_name")));

        String seventh = executor.statements(row(Map.of("full_name", "Jane Doe"))).get(0).entityId();
        String eighth = executor.statements(new Row(8L, Map.of("full_name", "Jane Doe"))).get(0).entityId();

        assertThat(seventh).isNotEqualTo(eighth);
        assertThat(person(List.of(), Map.of("name", column("full_name")))
                .statements(row(Map.of("full_name", "Jane Doe"))).get(0).entityId()).isEqualTo(seventh);
    }

    @Test
    public void test_the_row_scoped_id_recipe_is_pinned_to_a_literal_hash() {
        // Changing this value orphans every statement already stored under the id it replaces.
        String expected = "fcb781c296021312b1480ac878a03694f8a41c4f715d866a113bdf235b34dfd7839e7c5d4d5a1edd07bdb8d917e572a7";

        String actual = person(List.of(), Map.of("name", column("full_name")))
                .statements(row(Map.of("full_name", "Jane Doe"))).get(0).entityId();

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void test_two_keyless_entities_of_one_type_are_two_entities() {
        List<Statement> statements = new MappingExecutor(mapping(Map.of(
                "buyer", entity("Person", List.of(), Map.of("name", column("buyer_name"))),
                "seller", entity("Person", List.of(), Map.of("name", column("seller_name"))))))
                .statements(row(Map.of("buyer_name", "Jane Doe", "seller_name", "John Roe")));

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0).entityId()).isNotEqualTo(statements.get(1).entityId());
    }

    @Test
    public void test_a_blank_key_column_name_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of(" "), Map.of("name", column("full_name"))));

        assertThat(thrown.violations.toString()).contains("blank key column name");
    }

    @Test
    public void test_a_blank_column_name_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"), Map.of("name", column("\u200B"))));

        assertThat(thrown.violations.toString()).contains("blank column name");
    }

    @Test
    public void test_a_keyless_entity_with_a_blank_row_is_counted_empty_not_unidentified() {
        MappingExecutor executor = person(List.of(), Map.of("name", column("full_name")));

        assertThat(executor.statements(row(Map.of("full_name", "")))).isEmpty();
        assertThat(executor.skipped().get(ENTITY_EMPTY)).isEqualTo(1L);
        assertThat(executor.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(0L);
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
    public void test_a_literal_property_is_stored_with_no_column_of_origin() {
        Statement statement = of(person(List.of("passport"),
                Map.of("name", column("full_name"), "nationality", literal("fr")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))), "nationality");

        assertThat(statement.value()).isEqualTo("fr");
        assertThat(statement.provenance().column()).isEqualTo("");
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
    public void test_a_join_concatenates_the_columns_into_one_statement() {
        Statement statement = person(List.of("passport"),
                Map.of("name", joined(List.of("first_name", "last_name"), " ")))
                .statements(row(Map.of("passport", "AB123", "first_name", "Jane", "last_name", "Doe"))).get(0);

        assertThat(statement.value()).isEqualTo("Jane Doe");
        assertThat(statement.provenance().column()).isEqualTo("first_name,last_name");
    }

    @Test
    public void test_a_join_skips_a_blank_column_rather_than_doubling_the_separator() {
        Statement statement = person(List.of("passport"),
                Map.of("name", joined(List.of("first_name", "middle_name", "last_name"), " ")))
                .statements(row(Map.of("passport", "AB123", "first_name", "Jane", "middle_name", "",
                        "last_name", "Doe"))).get(0);

        assertThat(statement.value()).isEqualTo("Jane Doe");
    }

    @Test
    public void test_a_reference_stores_the_id_of_the_entity_it_names() {
        List<Statement> statements = new MappingExecutor(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))),
                "job", entity("Employment", List.of("passport", "siren"), Map.of(
                        "employee", reference("member"), "employer", reference("employer"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                        "siren", "552100554", "company", "ACME")));

        String person = statements.stream().filter(candidate -> candidate.entityType().equals("Person"))
                .findFirst().orElseThrow().entityId();
        Statement employee = of(statements, "employee");

        assertThat(employee.value()).isEqualTo(person);
        assertThat(employee.entityType()).isEqualTo("Employment");
        assertThat(employee.provenance().column()).isEqualTo("");
    }

    @Test
    public void test_an_edge_that_lost_an_endpoint_keeps_the_endpoint_it_has() {
        MappingExecutor executor = employment();

        List<Statement> statements = executor.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "siren", "", "company", "")));

        assertThat(statements.stream().map(Statement::entityType).distinct().sorted().toList())
                .isEqualTo(List.of("Employment", "Person"));
        assertThat(of(statements, "employee").entityType()).isEqualTo("Employment");
        assertThat(executor.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_reference_to_an_entity_that_stored_nothing_is_dropped() {
        MappingExecutor executor = employment();

        List<Statement> statements = executor.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "siren", "552100554", "company", "")));

        assertThat(statements.stream().map(Statement::property).sorted().toList())
                .isEqualTo(List.of("employee", "name"));
        assertThat(executor.skipped().get(ENTITY_EMPTY)).isEqualTo(1L);
    }

    @Test
    public void test_an_edge_that_loses_every_endpoint_is_dropped_too() {
        MappingExecutor executor = employment();

        List<Statement> statements = executor.statements(row(Map.of("passport", "AB123",
                "full_name", "", "siren", "552100554", "company", "")));

        assertThat(statements).isEmpty();
        assertThat(executor.skipped().get(ENTITY_EMPTY)).isEqualTo(3L);
    }

    @Test
    public void test_a_formatted_column_is_stored_as_iso_with_the_input_kept() {
        Statement statement = of(person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yyyy")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                        "born", "01/03/1970"))), "birthDate");

        assertThat(statement.value()).isEqualTo("1970-03-01");
        assertThat(statement.originalValue()).isEqualTo("01/03/1970");
    }

    @Test
    public void test_a_value_that_does_not_parse_is_stored_as_it_was_read_and_counted() {
        MappingExecutor executor = person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yyyy")));

        Statement statement = of(executor.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "born", "n/a"))), "birthDate");

        assertThat(statement.value()).isEqualTo("n/a");
        assertThat(statement.originalValue()).isNull();
        assertThat(executor.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
    }

    @Test
    public void test_a_day_the_month_does_not_have_is_kept_as_it_was_read() {
        MappingExecutor executor = person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yyyy")));

        Statement statement = of(executor.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "born", "31/02/1970"))), "birthDate");

        assertThat(statement.value()).isEqualTo("31/02/1970");
        assertThat(statement.originalValue()).isNull();
        assertThat(executor.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
    }

    @Test
    public void test_a_pattern_carrying_a_time_keeps_the_time() {
        Statement statement = of(person(List.of("passport"), Map.of("name", column("full_name"),
                        "birthDate", formatted("born", "dd/MM/yyyy HH:mm")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                        "born", "01/03/1970 14:30"))), "birthDate");

        assertThat(statement.value()).isEqualTo("1970-03-01T14:30");
        assertThat(statement.originalValue()).isEqualTo("01/03/1970 14:30");
    }

    @Test
    public void test_a_year_only_pattern_is_read_rather_than_left_alone() {
        MappingExecutor executor = person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "yyyy")));

        Statement statement = of(executor.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "born", "1970"))), "birthDate");

        assertThat(statement.value()).isEqualTo("1970");
        assertThat(executor.skipped().get(CELL_UNREADABLE)).isEqualTo(0L);
    }

    @Test
    public void test_a_year_and_month_pattern_is_read_rather_than_left_alone() {
        MappingExecutor executor = person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "MM/yyyy")));

        Statement statement = of(executor.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "born", "03/1970"))), "birthDate");

        assertThat(statement.value()).isEqualTo("1970-03");
        assertThat(statement.originalValue()).isEqualTo("03/1970");
        assertThat(executor.skipped().get(CELL_UNREADABLE)).isEqualTo(0L);
    }

    @Test
    public void test_a_zero_padded_cell_under_a_non_padded_pattern_still_converts() {
        MappingExecutor executor = person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "d/M/yyyy")));

        Statement statement = of(executor.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "born", "01/03/1970"))), "birthDate");

        assertThat(statement.value()).isEqualTo("1970-03-01");
        assertThat(executor.skipped().get(CELL_UNREADABLE)).isEqualTo(0L);
    }

    @Test
    public void test_a_year_below_one_thousand_is_stored_padded_to_iso() {
        Statement statement = of(person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "yyyy")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                        "born", "0070"))), "birthDate");

        assertThat(statement.value()).isEqualTo("0070");
    }

    @Test
    public void test_a_single_letter_year_pattern_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "birthDate", formatted("born", "d/M/y"))));

        assertThat(thrown.violations.toString()).contains("write the year in full");
    }

    @Test
    public void test_a_week_based_year_pattern_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/YYYY"))));

        assertThat(thrown.violations.toString()).contains("week-based");
    }

    @Test
    public void test_a_pattern_carrying_no_date_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "birthDate", formatted("born", "HH:mm"))));

        assertThat(thrown.violations.toString()).contains("unusable format");
    }

    @Test
    public void test_a_pattern_carrying_an_offset_fails_at_construction() {
        assertThrows(InvalidExtractionMapping.class, () -> person(List.of("passport"),
                Map.of("name", column("full_name"),
                        "birthDate", formatted("born", "yyyy-MM-dd'T'HH:mmXXX"))));
    }

    @Test
    public void test_a_proleptic_year_next_to_an_era_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "birthDate", formatted("born", "G uuuu"))));

        assertThat(thrown.violations.toString()).contains("use 'y'");
    }

    @Test
    public void test_an_era_pattern_reads_a_bc_year_as_bc() {
        Statement statement = of(person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "G yyyy")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                        "born", "BC 0044"))), "birthDate");

        assertThat(statement.value()).isEqualTo("-0043");
        assertThat(statement.originalValue()).isEqualTo("BC 0044");
    }

    @Test
    public void test_a_bce_year_is_stored_in_the_iso_expanded_form() {
        Statement statement = of(person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "yyyy")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                        "born", "-0070"))), "birthDate");

        assertThat(statement.value()).isEqualTo("-0070");
        assertThat(statement.originalValue()).isNull();
    }

    @Test
    public void test_a_two_digit_year_pattern_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yy"))));

        assertThat(thrown.violations.toString()).contains("two-digit year");
    }

    @Test
    public void test_a_quoted_literal_does_not_fuse_two_two_digit_years_into_a_full_year() {
        assertThrows(InvalidExtractionMapping.class, () -> person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "yy'x'yy"))));
    }

    @Test
    public void test_a_value_already_in_the_target_form_keeps_no_original() {
        Statement statement = of(person(List.of("passport"), Map.of("name", column("full_name"),
                        "birthDate", formatted("born", "yyyy-MM-dd")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                        "born", "1970-03-01"))), "birthDate");

        assertThat(statement.value()).isEqualTo("1970-03-01");
        assertThat(statement.originalValue()).isNull();
    }

    @Test
    public void test_a_text_pattern_parses_the_same_regardless_of_the_default_locale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.FRANCE);
        try {
            Statement statement = of(person(List.of("passport"), Map.of("name", column("full_name"),
                            "birthDate", formatted("born", "dd/MMM/yyyy")))
                    .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                            "born", "01/Mar/1970"))), "birthDate");

            assertThat(statement.value()).isEqualTo("1970-03-01");
            assertThat(statement.originalValue()).isEqualTo("01/Mar/1970");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void test_a_nul_document_id_fails_at_construction() {
        ExtractionMapping nulDocument = new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm",
                "doc\u00001", RowSourceOptions.defaults(),
                Map.of("member", entity("Person", List.of("passport"), Map.of("name", column("full_name")))));

        InvalidExtractionMapping thrown =
                assertThrows(InvalidExtractionMapping.class, () -> new MappingExecutor(nulDocument));
        assertThat(thrown.violations.toString()).contains("document id");
    }

    @Test
    public void test_a_nul_literal_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "nationality", literal("f\u0000r"))));

        assertThat(thrown.violations.toString()).contains("literal holding a NUL");
    }

    @Test
    public void test_a_nul_join_separator_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", joined(List.of("first_name", "last_name"), "\u0000"))));

        assertThat(thrown.violations.toString()).contains("join separator holding a NUL");
    }

    @Test
    public void test_a_nul_column_name_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"), Map.of("name", column("full\u0000name"))));

        assertThat(thrown.violations.toString()).contains("column name holding a NUL");
    }

    @Test
    public void test_a_nul_key_column_name_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("pass\u0000port"), Map.of("name", column("full_name"))));

        assertThat(thrown.violations.toString()).contains("key column name holding a NUL");
    }

    @Test
    public void test_a_non_breaking_space_literal_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "nationality", literal("\u00A0"))));

        assertThat(thrown.violations.toString()).contains("blank literal");
    }

    @Test
    public void test_a_padded_literal_is_stored_stripped_like_the_cell_it_mirrors() {
        Statement statement = of(person(List.of("passport"),
                Map.of("name", column("full_name"), "nationality", literal(" fr ")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))), "nationality");

        assertThat(statement.value()).isEqualTo("fr");
    }

    @Test
    public void test_a_blank_literal_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "nationality", literal(" "))));

        assertThat(thrown.violations.toString()).contains("blank literal");
    }

    @Test
    public void test_an_entity_that_maps_no_property_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> new MappingExecutor(mapping(Map.of(
                        "home", entity("Address", List.of("street"), Map.of())))));

        assertThat(thrown.violations.toString()).contains("maps no property");
    }

    @Test
    public void test_a_sheet_holding_a_nul_fails_at_construction() {
        ExtractionMapping mapping = new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm", "doc-1",
                new RowSourceOptions(null, null, null, null, "\u0000Sheet", null),
                Map.of("member", entity("Person", List.of("passport"), Map.of("name", column("full_name")))));

        assertThrows(IllegalArgumentException.class, () -> new MappingExecutor(mapping));
    }

    @Test
    public void test_a_mapping_that_no_longer_validates_fails_at_construction() {
        ExtractionMapping stale = mapping(Map.of("member",
                entity("Person", List.of("passport"), Map.of("hoofSize", column("hooves")))));

        InvalidExtractionMapping thrown =
                assertThrows(InvalidExtractionMapping.class, () -> new MappingExecutor(stale));
        assertThat(thrown.violations.toString()).contains("hoofSize");
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

    @Test
    public void test_an_unusable_format_names_the_pattern_it_refuses() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "birthDate", formatted("born", "HH:mm"))));

        assertThat(thrown.violations.toString()).contains("'HH:mm'");
    }

    @Test
    public void test_a_pattern_letter_that_does_not_exist_is_a_violation_not_a_crash() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "birthDate", formatted("born", "bbbb"))));

        assertThat(thrown.violations.toString()).contains("'bbbb'");
    }
}
