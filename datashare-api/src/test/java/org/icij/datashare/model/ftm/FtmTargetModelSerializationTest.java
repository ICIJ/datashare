package org.icij.datashare.model.ftm;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.TargetModel;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class FtmTargetModelSerializationTest {
    private final TargetModel model = new FtmTargetModel();

    @Test
    public void test_round_trips_a_person() {
        ModelEntity person = new ModelEntity("person-1", Set.of("Person"),
                Map.of("name", List.of("Jane Doe", "J. Doe"), "birthDate", List.of("1980-04-02")));

        assertThat(model.parse(model.serialize(person))).isEqualTo(person);
    }

    @Test
    public void test_serializes_the_ftm_entity_shape() {
        String json = model.serialize(new ModelEntity("person-1", Set.of("Person"),
                Map.of("name", List.of("Jane Doe"))));

        assertThat(json).contains("\"id\":\"person-1\"");
        assertThat(json).contains("\"schema\":\"Person\"");
        assertThat(json).contains("\"name\":[\"Jane Doe\"]");
    }

    @Test
    public void test_collapses_a_multi_type_entity_to_its_most_specific_type() {
        String json = model.serialize(new ModelEntity("person-1", Set.of("Person", "LegalEntity", "Thing"),
                Map.of("name", List.of("Jane Doe"))));

        assertThat(json).contains("\"schema\":\"Person\"");
    }

    @Test
    public void test_types_with_no_common_schema_are_a_violation() {
        ModelEntity confused = new ModelEntity("x-1", Set.of("Person", "Company"),
                Map.of("name", List.of("Jane Doe")));

        List<TargetModel.Violation> violations = model.validate(confused);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("no common schema");
    }

    @Test
    public void test_serializing_types_with_no_common_schema_fails() {
        assertThrowsContaining(() -> model.serialize(new ModelEntity("x-1", Set.of("Person", "Company"),
                Map.of("name", List.of("Jane Doe")))), "Person", "Company");
    }

    @Test
    public void test_unreadable_json_fails_with_a_clear_error() {
        assertThrowsContaining(() -> model.parse("{\"id\": "), "FtM");
    }

    @Test
    public void test_a_missing_id_fails_with_a_clear_error() {
        assertThrowsContaining(() -> model.parse("{\"schema\":\"Person\"}"), "id", "FtM");
    }

    @Test
    public void test_a_missing_schema_fails_with_a_clear_error() {
        assertThrowsContaining(() -> model.parse("{\"id\":\"person-1\"}"), "schema", "FtM");
    }

    @Test
    public void test_a_json_null_fails_with_a_clear_error() {
        assertThrowsContaining(() -> model.parse("null"), "FtM");
    }

    @Test
    public void test_a_property_with_null_values_fails_with_a_clear_error() {
        assertThrowsContaining(
                () -> model.parse("{\"id\":\"person-1\",\"schema\":\"Person\",\"properties\":{\"name\":null}}"),
                "name", "FtM");
    }

    @Test
    public void test_a_property_holding_a_null_value_fails_with_a_clear_error() {
        assertThrowsContaining(
                () -> model.parse("{\"id\":\"person-1\",\"schema\":\"Person\",\"properties\":{\"name\":[null]}}"),
                "name", "FtM");
    }

    @Test
    public void test_the_multi_type_entity_it_collapses_is_valid() {
        assertThat(model.validate(new ModelEntity("person-1", Set.of("Person", "LegalEntity", "Thing"),
                Map.of("name", List.of("Jane Doe"))))).isEmpty();
    }

    @Test
    public void test_an_absent_properties_reads_as_empty() {
        ModelEntity entity = model.parse("{\"id\":\"person-1\",\"schema\":\"Person\"}");

        assertThat(entity.properties()).isEqualTo(Map.of());
    }

    private static void assertThrowsContaining(Runnable action, String... messageParts) {
        String message = assertThrows(IllegalArgumentException.class, action::run).getMessage();
        for (String part : messageParts) {
            assertThat(message).contains(part);
        }
    }
}
