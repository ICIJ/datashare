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

/**
 * One row against one mapping: what a statement carries, when an entity converges on one id, and
 * what a run drops rather than stores.
 */
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
        assertThat(statements.get(0).model()).isEqualTo("ftm");
        assertThat(statements.get(0).entityType()).isEqualTo("Person");
        assertThat(statements.get(0).property()).isEqualTo("name");
        assertThat(statements.get(0).value()).isEqualTo("Jane Doe");
        assertThat(statements.get(0).originalValue()).isNull();
    }

    @Test
    public void test_a_statement_carries_the_document_the_sheet_the_row_and_the_column() {
        Statement statement = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0);

        assertThat(statement.provenance().documentId()).isEqualTo("doc-1");
        assertThat(statement.provenance().sheet()).isEqualTo("");
        assertThat(statement.provenance().rowNumber()).isEqualTo(7L);
        assertThat(statement.provenance().column()).isEqualTo("full_name");
    }

    @Test
    public void test_two_mappings_naming_the_key_column_differently_agree_on_the_entity_id() {
        String one = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).entityId();
        String two = person(List.of("passport_no"), Map.of("name", column("name")))
                .statements(row(Map.of("passport_no", "AB123", "name", "Jane Doe"))).get(0).entityId();

        assertThat(one).isEqualTo(two);
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
    public void test_two_key_cells_holding_each_other_s_value_are_two_entities() {
        String one = person(List.of("given", "family"), Map.of("name", column("full_name")))
                .statements(row(Map.of("given", "Jean", "family", "Pierre", "full_name", "Jean Pierre")))
                .get(0).entityId();
        String two = person(List.of("given", "family"), Map.of("name", column("full_name")))
                .statements(row(Map.of("given", "Pierre", "family", "Jean", "full_name", "Pierre Jean")))
                .get(0).entityId();

        assertThat(one).isNotEqualTo(two);
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
    public void test_surrounding_whitespace_in_a_value_does_not_split_the_statement() {
        String padded = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", " Jane Doe "))).get(0).id();
        String bare = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).id();

        assertThat(padded).isEqualTo(bare);
    }

    @Test
    public void test_two_types_keyed_on_the_same_value_are_two_entities() {
        String person = person(List.of("id"), Map.of("name", column("n")))
                .statements(row(Map.of("id", "AB123", "n", "Jane Doe"))).get(0).entityId();
        String company = new MappingExecutor(mapping(Map.of("employer",
                entity("Company", List.of("id"), Map.of("name", column("n"))))))
                .statements(row(Map.of("id", "AB123", "n", "Jane Doe"))).get(0).entityId();

        assertThat(person).isNotEqualTo(company);
    }

    @Test
    public void test_the_entity_id_recipe_is_pinned_to_a_literal_hash() {
        // Changing this value orphans every statement already stored under the id it replaces.
        String expected = "0ab69dcbafdfbe3c4938d377bf99eb9b1a539945328b234e4e2c7179cb9db85c9ed41936b1e06c4b7da27a6f4eb8f0a3";

        String actual = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).entityId();

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void test_a_row_whose_key_is_blank_yields_no_statement() {
        assertThat(person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "  ", "full_name", "Jane Doe")))).isEmpty();
    }

    @Test
    public void test_a_row_whose_key_is_blank_is_counted_as_unidentified() {
        MappingExecutor executor = person(List.of("passport"), Map.of("name", column("full_name")));

        executor.statements(row(Map.of("passport", "  ", "full_name", "Jane Doe")));

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
    public void test_only_the_unidentifiable_entity_of_two_is_counted_as_unidentified() {
        MappingExecutor executor = new MappingExecutor(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))))));

        executor.statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                "siren", "", "company", "ACME")));

        assertThat(executor.skipped().get(ENTITY_UNIDENTIFIED)).isEqualTo(1L);
    }

    @Test
    public void test_a_key_cell_holding_a_nul_yields_no_statement_and_is_counted() {
        MappingExecutor executor = person(List.of("passport"), Map.of("name", column("full_name")));

        List<Statement> statements = executor.statements(row(Map.of("passport", "A\u0000B",
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
    }

    @Test
    public void test_a_value_holding_only_a_non_breaking_space_yields_no_statement() {
        MappingExecutor executor = person(List.of("passport"),
                Map.of("name", column("full_name"), "email", column("mail")));

        List<Statement> statements = executor.statements(row(Map.of("passport", "AB123",
                "full_name", "\u00A0", "mail", "jane@example.org")));

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).property()).isEqualTo("email");
    }

    @Test
    public void test_a_blank_cell_yields_no_statement() {
        assertThat(person(List.of("passport"), Map.of("name", column("full_name"), "email", column("mail")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "mail", "")))).hasSize(1);
    }

    @Test
    public void test_a_row_that_fills_no_property_is_counted_as_empty() {
        MappingExecutor executor = person(List.of("passport"), Map.of("name", column("full_name")));

        assertThat(executor.statements(row(Map.of("passport", "AB123", "full_name", "")))).isEmpty();
        assertThat(executor.skipped().get(ENTITY_EMPTY)).isEqualTo(1L);
    }

    @Test
    public void test_a_second_row_adding_a_property_to_a_known_entity_keeps_it() {
        MappingExecutor executor = person(List.of("person_id"),
                Map.of("name", column("full_name"), "email", column("mail")));

        List<Statement> first = executor.statements(row(Map.of("person_id", "p-1",
                "full_name", "Jane Doe", "mail", "")));
        List<Statement> second = executor.statements(new Row(8L, Map.of("person_id", "p-1",
                "full_name", "", "mail", "jane@example.org")));

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).property()).isEqualTo("email");
        assertThat(second.get(0).entityId()).isEqualTo(first.get(0).entityId());
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
    public void test_a_join_skips_a_column_holding_only_a_non_breaking_space() {
        Statement statement = person(List.of("passport"),
                Map.of("name", joined(List.of("first_name", "middle_name", "last_name"), " ")))
                .statements(row(Map.of("passport", "AB123", "first_name", "Jane", "middle_name", "\u00A0",
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
    public void test_a_reference_to_an_unidentified_entity_yields_no_statement() {
        List<Statement> statements = employment().statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "siren", "", "company", "ACME")));

        assertThat(statements.stream().filter(candidate -> candidate.property().equals("employer")).toList())
                .isEmpty();
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
    public void test_a_two_digit_year_pattern_fails_at_construction() {
        InvalidExtractionMapping thrown = assertThrows(InvalidExtractionMapping.class,
                () -> person(List.of("passport"),
                        Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yy"))));

        assertThat(thrown.violations.toString()).contains("two-digit year");
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
    public void test_a_malformed_pattern_fails_at_construction_not_at_the_first_row() {
        assertThrows(IllegalArgumentException.class, () -> person(List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "not-a-pattern"))));
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
}
