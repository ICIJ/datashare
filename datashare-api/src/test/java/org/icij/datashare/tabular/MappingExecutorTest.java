package org.icij.datashare.tabular;

import org.icij.datashare.model.Statement;
import org.junit.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class MappingExecutorTest {

    static ExtractionMapping mapping(Map<String, ExtractionMapping.EntityMapping> entities) {
        return new ExtractionMapping("map-1", "prj", "jdoe", "staff", "ftm", "doc-1",
                RowSourceOptions.defaults(), entities);
    }

    static ExtractionMapping.EntityMapping entity(String type, List<String> keys,
                                                  Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new ExtractionMapping.EntityMapping(type, keys, properties);
    }

    static ExtractionMapping.PropertyMapping column(String name) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, null);
    }

    static ExtractionMapping.PropertyMapping literal(String value) {
        return new ExtractionMapping.PropertyMapping(List.of(), null, value, null, null);
    }

    static ExtractionMapping.PropertyMapping reference(String alias) {
        return new ExtractionMapping.PropertyMapping(List.of(), null, null, alias, null);
    }

    static ExtractionMapping.PropertyMapping joined(List<String> columns, String separator) {
        return new ExtractionMapping.PropertyMapping(columns, separator, null, null, null);
    }

    static ExtractionMapping.PropertyMapping formatted(String name, String pattern) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, pattern);
    }

    static Row row(Map<String, String> values) {
        return new Row(7L, values);
    }

    private static MappingExecutor person(List<String> keys, Map<String, ExtractionMapping.PropertyMapping> props) {
        return new MappingExecutor(mapping(Map.of("member", entity("Person", keys, props))));
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
    public void test_surrounding_whitespace_in_a_key_does_not_split_the_entity() {
        String one = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", " AB123 ", "full_name", "Jane Doe"))).get(0).entityId();
        String two = person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).get(0).entityId();

        assertThat(one).isEqualTo(two);
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
    public void test_a_row_whose_key_is_blank_is_counted_as_skipped() {
        MappingExecutor executor = person(List.of("passport"), Map.of("name", column("full_name")));

        executor.statements(row(Map.of("passport", "  ", "full_name", "Jane Doe")));

        assertThat(executor.skipped()).isEqualTo(1L);
    }

    @Test
    public void test_only_the_unidentifiable_entity_of_two_is_counted_as_skipped() {
        MappingExecutor executor = new MappingExecutor(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))))));

        executor.statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                "siren", "", "company", "ACME")));

        assertThat(executor.skipped()).isEqualTo(1L);
    }

    @Test
    public void test_a_key_cell_holding_a_nul_yields_no_statement_and_is_counted_as_skipped() {
        MappingExecutor executor = person(List.of("passport"), Map.of("name", column("full_name")));

        List<Statement> statements = executor.statements(row(Map.of("passport", "A\u0000B", "full_name", "Jane Doe")));

        assertThat(statements).isEmpty();
        assertThat(executor.skipped()).isEqualTo(1L);
    }

    @Test
    public void test_a_blank_cell_yields_no_statement() {
        assertThat(person(List.of("passport"), Map.of("name", column("full_name"), "email", column("mail")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "mail", "")))).hasSize(1);
    }

    @Test
    public void test_a_literal_property_is_stored_with_no_column_of_origin() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", column("full_name"), "nationality", literal("fr"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))).stream()
                .filter(candidate -> candidate.property().equals("nationality")).findFirst().orElseThrow();

        assertThat(statement.value()).isEqualTo("fr");
        assertThat(statement.provenance().column()).isEqualTo("");
    }

    @Test
    public void test_several_columns_without_a_join_are_several_statements() {
        List<Statement> statements = new MappingExecutor(mapping(Map.of("member", entity("Person",
                List.of("passport"), Map.of("name", new ExtractionMapping.PropertyMapping(
                        List.of("full_name", "maiden_name"), null, null, null, null))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "maiden_name", "Jane Roe")));

        assertThat(statements).hasSize(2);
        assertThat(statements.stream().map(Statement::value).sorted().toList())
                .isEqualTo(List.of("Jane Doe", "Jane Roe"));
        assertThat(statements.stream().map(statement -> statement.provenance().column()).sorted().toList())
                .isEqualTo(List.of("full_name", "maiden_name"));
    }

    @Test
    public void test_a_join_concatenates_the_columns_into_one_statement() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", joined(List.of("first_name", "last_name"), " "))))))
                .statements(row(Map.of("passport", "AB123", "first_name", "Jane", "last_name", "Doe"))).get(0);

        assertThat(statement.value()).isEqualTo("Jane Doe");
        assertThat(statement.provenance().column()).isEqualTo("first_name,last_name");
    }

    @Test
    public void test_a_join_skips_a_blank_column_rather_than_doubling_the_separator() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", joined(List.of("first_name", "middle_name", "last_name"), " "))))))
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

        String person = statements.stream().filter(s -> s.entityType().equals("Person"))
                .findFirst().orElseThrow().entityId();
        Statement employee = statements.stream().filter(s -> s.property().equals("employee"))
                .findFirst().orElseThrow();

        assertThat(employee.value()).isEqualTo(person);
        assertThat(employee.entityType()).isEqualTo("Employment");
        assertThat(employee.provenance().column()).isEqualTo("");
    }

    @Test
    public void test_a_reference_to_an_unidentified_entity_yields_no_statement() {
        List<Statement> statements = new MappingExecutor(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))),
                "job", entity("Employment", List.of("passport"), Map.of(
                        "employee", reference("member"), "employer", reference("employer"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe",
                        "siren", "", "company", "ACME")));

        assertThat(statements.stream().filter(s -> s.property().equals("employer")).toList()).isEmpty();
    }

    @Test
    public void test_a_formatted_column_is_stored_as_iso_with_the_input_kept() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yyyy"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "born", "01/03/1970")))
                .stream().filter(candidate -> candidate.property().equals("birthDate")).findFirst().orElseThrow();

        assertThat(statement.value()).isEqualTo("1970-03-01");
        assertThat(statement.originalValue()).isEqualTo("01/03/1970");
    }

    @Test
    public void test_a_value_that_does_not_parse_is_stored_as_it_was_read() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "dd/MM/yyyy"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "born", "n/a")))
                .stream().filter(candidate -> candidate.property().equals("birthDate")).findFirst().orElseThrow();

        assertThat(statement.value()).isEqualTo("n/a");
        assertThat(statement.originalValue()).isNull();
    }

    @Test
    public void test_a_value_already_in_the_target_form_keeps_no_original() {
        Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person", List.of("passport"),
                Map.of("name", column("full_name"), "birthDate", formatted("born", "yyyy-MM-dd"))))))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "born", "1970-03-01")))
                .stream().filter(candidate -> candidate.property().equals("birthDate")).findFirst().orElseThrow();

        assertThat(statement.value()).isEqualTo("1970-03-01");
        assertThat(statement.originalValue()).isNull();
    }

    @Test
    public void test_a_text_pattern_parses_the_same_regardless_of_the_default_locale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.FRANCE);
        try {
            Statement statement = new MappingExecutor(mapping(Map.of("member", entity("Person",
                    List.of("passport"), Map.of("name", column("full_name"),
                            "birthDate", formatted("born", "dd/MMM/yyyy"))))))
                    .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "born", "01/Mar/1970")))
                    .stream().filter(candidate -> candidate.property().equals("birthDate")).findFirst().orElseThrow();

            assertThat(statement.value()).isEqualTo("1970-03-01");
            assertThat(statement.originalValue()).isEqualTo("01/Mar/1970");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void test_a_malformed_pattern_fails_at_construction_not_at_the_first_row() {
        assertThrows(IllegalArgumentException.class, () -> new MappingExecutor(mapping(Map.of("member",
                entity("Person", List.of("passport"),
                        Map.of("name", column("full_name"), "birthDate", formatted("born", "not-a-pattern")))))));
    }

    @Test
    public void test_an_entity_missing_a_required_property_is_dropped_and_counted() {
        MappingExecutor executor = new MappingExecutor(mapping(Map.of("member",
                entity("Person", List.of("passport"), Map.of("name", column("full_name"))))));

        assertThat(executor.statements(row(Map.of("passport", "AB123", "full_name", "")))).isEmpty();
        assertThat(executor.skipped()).isEqualTo(1L);
    }

    @Test
    public void test_an_edge_that_lost_an_endpoint_is_dropped_but_the_entities_are_kept() {
        MappingExecutor executor = new MappingExecutor(mapping(Map.of(
                "member", entity("Person", List.of("passport"), Map.of("name", column("full_name"))),
                "employer", entity("Company", List.of("siren"), Map.of("name", column("company"))),
                "job", entity("Employment", List.of("passport"), Map.of(
                        "employee", reference("member"), "employer", reference("employer"))))));

        List<Statement> statements = executor.statements(row(Map.of("passport", "AB123",
                "full_name", "Jane Doe", "siren", "", "company", "")));

        assertThat(statements.stream().map(Statement::entityType).distinct().toList()).isEqualTo(List.of("Person"));
        assertThat(executor.skipped()).isEqualTo(2L);
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
        MappingExecutor executor = new MappingExecutor(mapping(Map.of("member",
                entity("Person", List.of("passport"), Map.of("name", column("fullname"))))));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> executor.statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe"))));
        assertThat(thrown.getMessage()).contains("fullname");
    }
}
