package org.icij.datashare.model.ftm;

import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.TargetModel;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;

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
        try {
            model.serialize(new ModelEntity("x-1", Set.of("Person", "Company"),
                    Map.of("name", List.of("Jane Doe"))));
            fail("should have refused to pick a schema");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Person");
            assertThat(e.getMessage()).contains("Company");
        }
    }

    @Test
    public void test_unreadable_json_fails_with_a_clear_error() {
        try {
            model.parse("{\"id\": ");
            fail("should have refused the truncated json");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("FtM");
        }
    }

    @Test
    public void test_a_missing_id_fails_with_a_clear_error() {
        try {
            model.parse("{\"schema\":\"Person\"}");
            fail("should have refused the missing id");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("id");
            assertThat(e.getMessage()).contains("FtM");
        }
    }

    @Test
    public void test_a_missing_schema_fails_with_a_clear_error() {
        try {
            model.parse("{\"id\":\"person-1\"}");
            fail("should have refused the missing schema");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("schema");
            assertThat(e.getMessage()).contains("FtM");
        }
    }

    @Test
    public void test_an_absent_properties_reads_as_empty() {
        ModelEntity entity = model.parse("{\"id\":\"person-1\",\"schema\":\"Person\"}");

        assertThat(entity.properties()).isEqualTo(Map.of());
    }
}
