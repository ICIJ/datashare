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
    private static final TargetModel model = new FtmTargetModel();

    @Test
    public void test_round_trips_a_person() {
        ModelEntity person = new ModelEntity("ftm", "person-1", "Person", Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe", "J. Doe"), "birthDate", List.of("1980-04-02")));

        assertThat(model.parse(model.serialize(person))).isEqualTo(person);
    }

    @Test
    public void test_serializes_the_ftm_entity_shape() {
        String json = model.serialize(new ModelEntity("ftm", "person-1", "Person", Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe"))));

        assertThat(json).contains("\"id\":\"person-1\"");
        assertThat(json).contains("\"schema\":\"Person\"");
        assertThat(json).contains("\"name\":[\"Jane Doe\"]");
    }

    @Test
    public void test_serializing_a_type_the_model_does_not_declare_fails() {
        assertThrowsContaining(() -> model.serialize(new ModelEntity("ftm", "x-1", "Robot", Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe")))), "Robot", "FtM");
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
