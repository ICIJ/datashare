package org.icij.datashare.tabular;

import org.icij.datashare.model.Statement;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

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
    public void test_a_row_whose_key_is_blank_yields_no_statement() {
        assertThat(person(List.of("passport"), Map.of("name", column("full_name")))
                .statements(row(Map.of("passport", "  ", "full_name", "Jane Doe")))).isEmpty();
    }

    @Test
    public void test_a_blank_cell_yields_no_statement() {
        assertThat(person(List.of("passport"), Map.of("name", column("full_name"), "email", column("mail")))
                .statements(row(Map.of("passport", "AB123", "full_name", "Jane Doe", "mail", "")))).hasSize(1);
    }
}
