package org.icij.datashare.tabular;

import org.icij.datashare.model.Statement;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tabular.StatementBuilder.Skip.CELL_MISSING;
import static org.icij.datashare.tabular.StatementBuilder.Skip.CELL_UNREADABLE;
import static org.icij.datashare.tabular.StatementBuilder.Skip.ENTITY_EMPTY;
import static org.icij.datashare.tabular.StatementBuilder.Skip.ENTITY_UNIDENTIFIED;
import static org.junit.Assert.assertThrows;

public class StatementBuilderTest {

    private static ExtractionMapping mapping(Map<String, ExtractionMapping.EntityMapping> entities) {
        return new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm", "doc-1", null,
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
        return row(7L, values);
    }

    private static Row row(long number, Map<String, String> values) {
        Map<String, List<String>> cells = new LinkedHashMap<>();
        values.forEach((column, value) -> cells.put(column, List.of(value)));
        return new Row(number, cells);
    }

    private static StatementBuilder person(List<String> keys,
                                          Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new StatementBuilder(mapping(Map.of("member", entity("Person", keys, properties))), "");
    }

    private static StatementBuilder employment() {
        return new StatementBuilder(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))),
                "job", entity("Employment", List.of("passport"), Map.of(
                        "employee", reference("member"), "employer", reference("employer"))))), "");
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
        List<Statement> statements = new StatementBuilder(mapping(Map.of(
                "supplier", entity("Company", List.of("supplier_ref"), Map.of("name", column("supplier_name"))),
                "customer", entity("Company", List.of("customer_ref"), Map.of("name", column("customer_name"))))), "")
                .statements(row(Map.of("supplier_ref", "42", "customer_ref", "42",
                        "supplier_name", "Acme", "customer_name", "Globex")));

        assertThat(statements.get(0).entityId()).isEqualTo(statements.get(1).entityId());
    }

    @Test
    public void test_a_key_literal_tells_two_aliases_of_one_type_keyed_alike_apart() {
        List<Statement> statements = new StatementBuilder(mapping(Map.of(
                "supplier", keyed("Company", "supplier", List.of("supplier_ref"),
                        Map.of("name", column("supplier_name"))),
                "customer", keyed("Company", "customer", List.of("customer_ref"),
                        Map.of("name", column("customer_name"))))), "")
                .statements(row(Map.of("supplier_ref", "42", "customer_ref", "42",
                        "supplier_name", "Acme", "customer_name", "Globex")));

        assertThat(statements.get(0).entityId()).isNotEqualTo(statements.get(1).entityId());
    }

    @Test
    public void test_an_invisible_character_in_a_key_literal_does_not_split_the_entity() {
        String padded = new StatementBuilder(mapping(Map.of("supplier",
                keyed("Company", " supp\u200Blier ", List.of("supplier_ref"),
                        Map.of("name", column("supplier_name"))))), "")
                .statements(row(Map.of("supplier_ref", "42", "supplier_name", "Acme"))).get(0).entityId();
        String plain = new StatementBuilder(mapping(Map.of("supplier",
                keyed("Company", "supplier", List.of("supplier_ref"),
                        Map.of("name", column("supplier_name"))))), "")
                .statements(row(Map.of("supplier_ref", "42", "supplier_name", "Acme"))).get(0).entityId();

        assertThat(padded).isEqualTo(plain);
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
    public void test_two_tables_of_one_document_do_not_share_an_entity() {
        ExtractionMapping keyless = new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm", "doc-1", null,
                RowSourceOptions.defaults(), Map.of("member",
                entity("Person", List.of(), Map.of("name", column("full_name")))));

        String first = new StatementBuilder(keyless, "1")
                .statements(row(Map.of("full_name", "Jane Doe"))).get(0).entityId();
        String second = new StatementBuilder(keyless, "2")
                .statements(row(Map.of("full_name", "Jane Doe"))).get(0).entityId();

        assertThat(first).isNotEqualTo(second);
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
        StatementBuilder builder = person(List.of(), Map.of("name", column("full_name")));

        String seventh = builder.statements(row(Map.of("full_name", "Jane Doe"))).get(0).entityId();
        String eighth = builder.statements(row(8L, Map.of("full_name", "Jane Doe"))).get(0).entityId();

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
        List<Statement> statements = new StatementBuilder(mapping(Map.of(
                "buyer", entity("Person", List.of(), Map.of("name", column("buyer_name"))),
                "seller", entity("Person", List.of(), Map.of("name", column("seller_name"))))), "")
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
        StatementBuilder builder = person(List.of(), Map.of("name", column("full_name")));

        assertThat(builder.statements(row(Map.of("full_name", "")))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_EMPTY)).isEqualTo(1L);
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(0L);
    }

    @Test
    public void test_a_row_whose_key_is_blank_yields_no_statement_and_is_counted() {
        StatementBuilder builder = person(List.of("passport"), Map.of("name", column("full_name")));

        assertThat(builder.statements(row(Map.of("passport", "  ", "full_name", "Jane Doe")))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_multi_key_missing_one_value_identifies_nothing() {
        // Hashing the values that are left instead would merge every row missing that column into
        // one entity, and make the row indistinguishable from a row of the same type keyed on the
        // one column that is filled. A counted loss beats a silent merge.
        StatementBuilder builder = person(List.of("passport", "country"), Map.of("name", column("full_name")));

        assertThat(builder.statements(row(Map.of("passport", "", "country", "FR",
                "full_name", "Jane Doe")))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_key_holding_only_a_non_breaking_space_is_not_an_identifier() {
        StatementBuilder builder = person(List.of("passport"), Map.of("name", column("full_name")));

        List<Statement> statements = builder.statements(row(Map.of("passport", "\u00A0",
                "full_name", "Jane Doe")));

        assertThat(statements).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_value_cell_holding_a_nul_does_not_cost_the_run() {
        StatementBuilder builder = person(List.of("passport"),
                Map.of("name", column("full_name"), "email", column("mail")));

        List<Statement> statements = builder.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane\u0000Doe", "mail", "jane@example.org")));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).property()).isEqualTo("email");
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
    }

    @Test
    public void test_a_key_cell_holding_a_nul_yields_no_statement_and_is_counted() {
        StatementBuilder builder = person(List.of("passport"), Map.of("name", column("full_name")));

        assertThat(builder.statements(row(Map.of("passport", "AB\u0000123", "full_name", "Jane Doe")))).isEmpty();
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
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
        StatementBuilder builder = person(List.of("passport"), Map.of("name", column("full_name")));

        assertThat(builder.statements(row(Map.of("passport", "AB123", "full_name", "")))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_EMPTY)).isEqualTo(1L);
    }

    @Test
    public void test_two_aliases_of_one_entity_do_not_repeat_a_statement() {
        List<Statement> statements = new StatementBuilder(mapping(Map.of(
                "buyer", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "seller", entity("Person", List.of("passport"), Map.of("name", column("full_name"))))), "")
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
        List<Statement> statements = new StatementBuilder(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))),
                "job", entity("Employment", List.of("passport", "siren"), Map.of(
                        "employee", reference("member"), "employer", reference("employer"))))), "")
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
        StatementBuilder builder = employment();

        List<Statement> statements = builder.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "siren", "", "company", "")));

        assertThat(statements.stream().map(Statement::entityType).distinct().sorted().toList())
                .isEqualTo(List.of("Employment", "Person"));
        assertThat(of(statements, "employee").entityType()).isEqualTo("Employment");
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_reference_to_an_entity_that_stored_nothing_is_dropped() {
        StatementBuilder builder = employment();

        List<Statement> statements = builder.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "siren", "552100554", "company", "")));

        assertThat(statements.stream().map(Statement::property).sorted().toList())
                .isEqualTo(List.of("employee", "name"));
        assertThat(builder.skipped().get(ENTITY_EMPTY)).isEqualTo(1L);
    }

    @Test
    public void test_an_edge_that_loses_every_endpoint_is_dropped_too() {
        StatementBuilder builder = employment();

        List<Statement> statements = builder.statements(row(Map.of("passport", "AB123",
                "full_name", "", "siren", "552100554", "company", "")));

        assertThat(statements).isEmpty();
        assertThat(builder.skipped().get(ENTITY_EMPTY)).isEqualTo(3L);
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
        StatementBuilder builder = person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yyyy")));

        Statement statement = of(builder.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "born", "n/a"))), "birthDate");

        assertThat(statement.value()).isEqualTo("n/a");
        assertThat(statement.originalValue()).isNull();
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
    }

    @Test
    public void test_a_day_the_month_does_not_have_is_kept_as_it_was_read() {
        StatementBuilder builder = person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yyyy")));

        Statement statement = of(builder.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "born", "31/02/1970"))), "birthDate");

        assertThat(statement.value()).isEqualTo("31/02/1970");
        assertThat(statement.originalValue()).isNull();
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
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
        StatementBuilder builder = person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "yyyy")));

        Statement statement = of(builder.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "born", "1970"))), "birthDate");

        assertThat(statement.value()).isEqualTo("1970");
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(0L);
    }

    @Test
    public void test_a_year_and_month_pattern_is_read_rather_than_left_alone() {
        StatementBuilder builder = person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "MM/yyyy")));

        Statement statement = of(builder.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "born", "03/1970"))), "birthDate");

        assertThat(statement.value()).isEqualTo("1970-03");
        assertThat(statement.originalValue()).isEqualTo("03/1970");
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(0L);
    }

    @Test
    public void test_a_zero_padded_cell_under_a_non_padded_pattern_still_converts() {
        StatementBuilder builder = person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "d/M/yyyy")));

        Statement statement = of(builder.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "born", "01/03/1970"))), "birthDate");

        assertThat(statement.value()).isEqualTo("1970-03-01");
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(0L);
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

        assertThat(thrown.violations.toString()).contains("unusable date format");
    }

    @Test
    public void test_a_pattern_carrying_an_offset_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"), Map.of("name", column("full_name"),
                        "birthDate", formatted("born", "yyyy-MM-dd'T'HH:mmXXX"))));

        assertThat(thrown.violations.toString()).contains("an offset is a precision");
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
                "doc\u00001", null, RowSourceOptions.defaults(),
                Map.of("member", entity("Person", List.of("passport"), Map.of("name", column("full_name")))));

        InvalidExtractionMapping thrown =
                assertThrows(InvalidExtractionMapping.class, () -> new StatementBuilder(nulDocument, ""));
        assertThat(thrown.violations.toString()).contains("document id");
    }

    @Test
    public void test_a_nul_literal_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "nationality", literal("f\u0000r"))));

        assertThat(thrown.violations.toString()).contains("the literal of property 'nationality' on entity 'member' holds a NUL");
    }

    @Test
    public void test_a_nul_join_separator_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", joined(List.of("first_name", "last_name"), "\u0000"))));

        assertThat(thrown.violations.toString()).contains("the join separator of property 'name' on entity 'member' holds a NUL");
    }

    @Test
    public void test_a_nul_column_name_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"), Map.of("name", column("full\u0000name"))));

        assertThat(thrown.violations.toString()).contains("the column name of property 'name' on entity 'member' holds a NUL");
    }

    @Test
    public void test_a_nul_key_column_name_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("pass\u0000port"), Map.of("name", column("full_name"))));

        assertThat(thrown.violations.toString()).contains("the key column name of entity 'member' holds a NUL");
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
    public void test_a_blank_key_literal_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> new StatementBuilder(mapping(Map.of("supplier",
                        keyed("Company", "\u00A0", List.of("supplier_ref"),
                                Map.of("name", column("supplier_name"))))), ""));

        assertThat(thrown.violations.toString()).contains("blank key literal");
    }

    @Test
    public void test_an_entity_that_maps_no_property_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> new StatementBuilder(mapping(Map.of(
                        "home", entity("Address", List.of("street"), Map.of()))), ""));

        assertThat(thrown.violations.toString()).contains("maps no property");
    }

    @Test
    public void test_a_sheet_holding_a_nul_fails_at_construction() {
        ExtractionMapping mapping = new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm", "doc-1", null,
                new RowSourceOptions(null, null, null, null, "\u0000Sheet", null),
                Map.of("member", entity("Person", List.of("passport"), Map.of("name", column("full_name")))));

        assertThrows(IllegalArgumentException.class, () -> new StatementBuilder(mapping, ""));
    }

    @Test
    public void test_a_resolved_sheet_written_with_an_invisible_character_keeps_one_entity() {
        ExtractionMapping keyless = new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm", "doc-1", null,
                RowSourceOptions.defaults(),
                Map.of("member", entity("Person", List.of(), Map.of("name", column("full_name")))));

        String plain = new StatementBuilder(keyless, "Sheet 1")
                .statements(row(Map.of("full_name", "Jane Doe"))).get(0).entityId();
        String invisible = new StatementBuilder(keyless, " Sheet\u00A01\u200B")
                .statements(row(Map.of("full_name", "Jane Doe"))).get(0).entityId();

        assertThat(invisible).isEqualTo(plain);
    }

    @Test
    public void test_a_resolved_sheet_holding_a_nul_fails_at_construction() {
        ExtractionMapping mapping = mapping(Map.of("member",
                entity("Person", List.of("passport"), Map.of("name", column("full_name")))));

        assertThrows(IllegalArgumentException.class, () -> new StatementBuilder(mapping, "Sheet\u00001"));
    }

    @Test
    public void test_a_mapping_that_no_longer_validates_fails_at_construction() {
        ExtractionMapping stale = mapping(Map.of("member",
                entity("Person", List.of("passport"), Map.of("hoofSize", column("hooves")))));

        InvalidExtractionMapping thrown =
                assertThrows(InvalidExtractionMapping.class, () -> new StatementBuilder(stale, ""));
        assertThat(thrown.violations.toString()).contains("hoofSize");
    }

    @Test
    public void test_a_column_the_source_does_not_have_fails_the_run() {
        StatementBuilder builder = person(List.of("passport"), Map.of("name", column("fullname")));

        assertThat(builder.statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe")))).isEmpty();

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, builder::requireEveryColumnSeen);
        assertThat(thrown.getMessage()).contains("fullname");
    }

    @Test
    public void test_a_column_absent_from_the_first_row_but_present_later_imports() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", keyed("Person", null, List.of("id"), Map.of("name", column("name"))),
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("legal_name"))))), "");

        assertThat(builder.statements(row(1L, Map.of("id", "1", "name", "Jane")))).hasSize(1);
        assertThat(builder.statements(row(2L, Map.of("id", "2", "legal_name", "ACME")))).hasSize(1);
        builder.requireEveryColumnSeen();
    }

    @Test
    public void test_a_column_in_no_row_at_all_fails_at_end_of_run() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", keyed("Person", null, List.of("id"),
                                Map.of("name", column("name"), "country", column("mispelled"))))), "");

        builder.statements(row(1L, Map.of("id", "1", "name", "Jane")));

        InvalidExtractionMapping refusal =
                assertThrows(InvalidExtractionMapping.class, builder::requireEveryColumnSeen);
        assertThat(refusal.getMessage()).contains("mispelled");
    }

    @Test
    public void test_every_column_in_no_row_is_named_at_once() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", keyed("Person", null, List.of("id"),
                                Map.of("name", column("one"), "country", column("two"))))), "");

        builder.statements(row(1L, Map.of("id", "1")));

        InvalidExtractionMapping refusal =
                assertThrows(InvalidExtractionMapping.class, builder::requireEveryColumnSeen);
        assertThat(refusal.getMessage()).contains("one");
        assertThat(refusal.getMessage()).contains("two");
    }

    @Test
    public void test_a_column_missing_from_a_row_is_counted_once() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", keyed("Person", null, List.of("id"),
                                Map.of("name", column("name"), "country", column("country"))))), "");

        builder.statements(row(1L, Map.of("id", "1", "name", "Jane")));
        builder.statements(row(2L, Map.of("id", "2", "name", "John")));

        assertThat(builder.skipped().get(CELL_MISSING)).isEqualTo(1L);
    }

    @Test
    public void test_a_row_that_omits_a_column_the_first_row_had_is_counted_not_fatal() {
        StatementBuilder builder = person(List.of("passport"),
                Map.of("name", column("full_name"), "email", column("mail")));

        builder.statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                "mail", "jane@example.org")));
        List<Statement> second = builder.statements(row(8L, Map.of("passport", "AB124",
                "full_name", "John Roe")));

        assertThat(second).hasSize(1);
        assertThat(builder.skipped().get(CELL_MISSING)).isEqualTo(1L);
    }

    @Test
    public void test_a_column_the_source_stops_carrying_is_counted_once_not_once_per_row() {
        StatementBuilder builder = person(List.of("passport"),
                Map.of("name", column("full_name"), "email", column("mail")));

        builder.statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                "mail", "jane@example.org")));
        builder.statements(row(8L, Map.of("passport", "AB124", "full_name", "John Roe")));
        builder.statements(row(9L, Map.of("passport", "AB125", "full_name", "Ann Poe")));

        assertThat(builder.skipped().get(CELL_MISSING)).isEqualTo(1L);
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

    private static Row multi(Map<String, List<String>> values) {
        return new Row(7L, values);
    }

    @Test
    public void test_a_multi_valued_column_writes_one_statement_per_value() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("names"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1"), "names", List.of("ACME", "Acme SA"))));

        assertThat(statements).hasSize(2);
        assertThat(statements.stream().map(Statement::value).toList()).isEqualTo(List.of("ACME", "Acme SA"));
        assertThat(statements.stream().map(Statement::entityId).distinct().toList()).hasSize(1);
    }

    @Test
    public void test_two_values_cleaning_to_the_same_string_write_one_statement() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("names"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1"), "names", List.of("ACME", "ACME "))));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).value()).isEqualTo("ACME");
    }

    @Test
    public void test_a_blank_value_inside_a_column_writes_nothing() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("names"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1"), "names", List.of("", "ACME"))));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).value()).isEqualTo("ACME");
    }

    @Test
    public void test_join_runs_across_columns_then_across_each_column_values() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"),
                                 Map.of("name", joined(List.of("first", "second"), " "))))), "");

        List<Statement> statements = builder.statements(multi(Map.of(
                "id", List.of("e1"), "first", List.of("a", "b"), "second", List.of("c", ""))));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).value()).isEqualTo("a b c");
    }

    @Test
    public void test_a_multi_valued_key_column_refuses_the_entity() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("names"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1", "e2"), "names", List.of("ACME"))));

        assertThat(statements).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_date_format_applies_to_every_value_of_a_column() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", keyed("Person", null, List.of("id"),
                                Map.of("name", column("id"), "birthDate", formatted("dates", "dd/MM/yyyy"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1"), "dates", List.of("01/01/1970", "02/02/1980"))));

        assertThat(statements.stream().filter(statement -> statement.property().equals("birthDate"))
                           .map(Statement::value).toList())
                .isEqualTo(List.of("1970-01-01", "1980-02-02"));
    }

    @Test
    public void test_a_nul_in_one_value_drops_that_value_only() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", keyed("Company", null, List.of("id"), Map.of("name", column("names"))))), "");

        List<Statement> statements = builder.statements(
                multi(Map.of("id", List.of("e1"), "names", List.of("A\u0000B", "ACME"))));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).value()).isEqualTo("ACME");
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
    }

    private static ExtractionMapping.EntityMapping identified(String type, String idColumn,
                                                              Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new ExtractionMapping.EntityMapping(type, null, List.of(), idColumn, properties);
    }

    @Test
    public void test_an_id_column_becomes_the_entity_id_verbatim() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");

        List<Statement> statements = builder.statements(row(Map.of("id", "a1b2c3", "name", "ACME")));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).entityId()).isEqualTo("a1b2c3");
    }

    @Test
    public void test_an_edge_resolves_to_the_ids_its_columns_hold() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "ownership", identified("Ownership", "id",
                                        Map.of("owner", column("owner"), "asset", column("asset"))))), "");

        List<Statement> statements = builder.statements(
                row(Map.of("id", "edge-1", "owner", "person-1", "asset", "company-1")));

        assertThat(statements.stream().map(Statement::value).sorted().toList())
                .isEqualTo(List.of("company-1", "person-1"));
        assertThat(statements.stream().map(Statement::entityId).distinct().toList()).isEqualTo(List.of("edge-1"));
    }

    @Test
    public void test_a_blank_id_column_refuses_the_entity() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");

        assertThat(builder.statements(row(Map.of("id", "", "name", "ACME")))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_multi_valued_id_column_refuses_the_entity() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");

        assertThat(builder.statements(multi(Map.of("id", List.of("a", "b"), "name", List.of("ACME"))))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_nul_bearing_id_column_refuses_the_entity() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");

        assertThat(builder.statements(row(Map.of("id", "a\u0000b", "name", "ACME")))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
        assertThat(builder.skipped().get(CELL_UNREADABLE)).isEqualTo(1L);
    }

    @Test
    public void test_an_id_longer_than_the_column_allows_refuses_the_entity() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");

        assertThat(builder.statements(row(Map.of("id", "x".repeat(Statement.MAX_ENTITY_ID_LENGTH + 1),
                                                 "name", "ACME")))).isEmpty();
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_an_id_of_exactly_the_maximum_length_is_kept() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "company", identified("Company", "id", Map.of("name", column("name"))))), "");
        String longest = "x".repeat(Statement.MAX_ENTITY_ID_LENGTH);

        assertThat(builder.statements(row(Map.of("id", longest, "name", "ACME"))).get(0).entityId())
                .isEqualTo(longest);
    }

    private static ExtractionMapping.EntityMapping filtered(String type, String idColumn, Map<String, String> filters,
                                                            Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new ExtractionMapping.EntityMapping(type, null, List.of(), idColumn, filters, properties);
    }

    @Test
    public void test_an_alias_contributes_only_on_rows_its_filter_matches() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("schema", "Person"), Map.of("name", column("name"))),
                "company", filtered("Company", "id", Map.of("schema", "Company"), Map.of("name", column("name"))))),
                "");

        List<Statement> statements = builder.statements(
                row(Map.of("id", "e1", "schema", "Person", "name", "Jane")));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).entityType()).isEqualTo("Person");
    }

    @Test
    public void test_a_filtered_out_alias_is_not_counted_as_skipped() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("schema", "Person"), Map.of("name", column("name"))),
                "company", filtered("Company", "id", Map.of("schema", "Company"), Map.of("name", column("name"))))),
                "");

        builder.statements(row(Map.of("id", "e1", "schema", "Person", "name", "Jane")));

        assertThat(builder.skipped().get(ENTITY_EMPTY)).isEqualTo(0L);
        assertThat(builder.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(0L);
    }

    @Test
    public void test_a_multi_valued_filter_column_matches_on_any_value() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("schema", "Person"), Map.of("name", column("name"))))), "");

        List<Statement> statements = builder.statements(multi(Map.of(
                "id", List.of("e1"), "schema", List.of("LegalEntity", "Person"), "name", List.of("Jane"))));

        assertThat(statements).hasSize(1);
    }

    @Test
    public void test_a_filter_on_a_column_the_row_does_not_carry_matches_nothing() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("schema", "Person"), Map.of("name", column("name"))))), "");

        assertThat(builder.statements(row(Map.of("id", "e1", "name", "Jane")))).isEmpty();
    }

    @Test
    public void test_a_filter_matches_on_the_cleaned_value() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("schema", "Person"), Map.of("name", column("name"))))), "");

        assertThat(builder.statements(row(Map.of("id", "e1", "schema", " Person ", "name", "Jane"))))
                .hasSize(1);
    }

    @Test
    public void test_a_misspelled_filter_column_fails_at_end_of_run() {
        StatementBuilder builder = new StatementBuilder(mapping(Map.of(
                "person", filtered("Person", "id", Map.of("shcema", "Person"), Map.of("name", column("name"))))), "");

        builder.statements(row(Map.of("id", "e1", "schema", "Person", "name", "Jane")));

        InvalidExtractionMapping refusal =
                assertThrows(InvalidExtractionMapping.class, builder::requireEveryColumnSeen);
        assertThat(refusal.getMessage()).contains("shcema");
    }
}
