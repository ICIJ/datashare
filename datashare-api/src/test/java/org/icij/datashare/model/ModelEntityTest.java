package org.icij.datashare.model;

import org.junit.Test;

import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class ModelEntityTest {
    @Test
    public void test_groups_the_values_of_a_property_in_order() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                statement("birthDate", "1980-04-02", "dob"),
                statement("name", "J. Doe", "known_as")));

        assertThat(entity.id()).isEqualTo("person-1");
        assertThat(entity.types()).containsOnly("Person");
        assertThat(entity.properties().get("name")).containsExactly("Jane Doe", "J. Doe");
        assertThat(entity.properties().get("birthDate")).containsExactly("1980-04-02");
    }

    @Test
    public void test_unions_the_types_of_the_statements() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                Statement.of("ftm", "person-1", "LegalEntity", "name", "Jane Doe",
                        new Statement.Provenance("doc-2", null, 3, "counterparty"))));

        assertThat(entity.types()).containsOnly("Person", "LegalEntity");
    }

    @Test
    public void test_the_same_value_from_two_cells_is_two_statements_but_one_value() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                statement("name", "Jane Doe", "legal_name")));

        assertThat(entity.properties().get("name")).containsExactly("Jane Doe");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_refuses_statements_belonging_to_two_entities() {
        ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                Statement.of("ftm", "person-2", "Person", "name", "John Doe",
                        new Statement.Provenance("doc-1", "Sheet1", 13, "full_name"))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_refuses_an_empty_collection() {
        ModelEntity.from(List.of());
    }

    private Statement statement(String property, String value, String column) {
        return Statement.of("ftm", "person-1", "Person", property, value,
                new Statement.Provenance("doc-1", "Sheet1", 12, column));
    }
}
