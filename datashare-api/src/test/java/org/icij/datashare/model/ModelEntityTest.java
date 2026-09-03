package org.icij.datashare.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class ModelEntityTest {
    @Test
    public void test_the_entity_copies_the_collections_it_was_handed() {
        ModelEntity entity = new ModelEntity("ftm", "p-1", "Person",
                new HashSet<>(Set.of("4.10.2")), new HashSet<>(Set.of("doc-1")),
                new HashMap<>(Map.of("name", new ArrayList<>(List.of("Jane Doe")))));

        assertThrows(UnsupportedOperationException.class, () -> entity.modelVersions().add("4.11.0"));
        assertThrows(UnsupportedOperationException.class, () -> entity.documentIds().add("doc-2"));
        assertThrows(UnsupportedOperationException.class,
                () -> entity.properties().put("birthDate", List.of("1980-04-02")));
        assertThrows(UnsupportedOperationException.class, () -> entity.properties().get("name").add("J. Doe"));
    }

    @Test
    public void test_groups_the_values_of_a_property_in_natural_order() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                statement("birthDate", "1980-04-02", "dob"),
                statement("name", "J. Doe", "known_as")), Set.of("4.10.2"));

        assertThat(entity.model()).isEqualTo("ftm");
        assertThat(entity.id()).isEqualTo("person-1");
        assertThat(entity.type()).isEqualTo("Person");
        assertThat(entity.properties().get("name")).containsExactly("J. Doe", "Jane Doe");
        assertThat(entity.properties().get("birthDate")).containsExactly("1980-04-02");
    }

    @Test
    public void test_refuses_statements_giving_the_entity_two_types() {
        String message = assertThrows(IllegalArgumentException.class, () -> ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                Statement.of("ftm", "person-1", "LegalEntity", "name", "Jane Doe",
                        new Statement.Provenance("doc-2", null, 3, "counterparty"))), Set.of("4.10.2")))
                .getMessage();

        assertThat(message).contains("Person");
        assertThat(message).contains("LegalEntity");
    }

    @Test
    public void test_the_same_value_from_two_cells_is_two_statements_but_one_value() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                statement("name", "Jane Doe", "legal_name")), Set.of("4.10.2"));

        assertThat(entity.properties().get("name")).containsExactly("Jane Doe");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_refuses_statements_belonging_to_two_entities() {
        ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                Statement.of("ftm", "person-2", "Person", "name", "John Doe",
                        new Statement.Provenance("doc-1", "Sheet1", 13, "full_name"))), Set.of("4.10.2"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_refuses_an_empty_collection() {
        ModelEntity.from(List.of(), Set.of("4.10.2"));
    }

    @Test
    public void test_the_null_arguments_are_rejected() {
        Set<String> versions = Set.of("4.10.2");
        Set<String> documents = Set.of("doc-1");
        Map<String, List<String>> name = Map.of("name", List.of("Jane Doe"));

        assertRejectsNull("model", () -> new ModelEntity(null, "p-1", "Person", versions, documents, name));
        assertRejectsNull("id", () -> new ModelEntity("ftm", null, "Person", versions, documents, name));
        assertRejectsNull("type", () -> new ModelEntity("ftm", "p-1", null, versions, documents, name));
        assertRejectsNull("modelVersions", () -> new ModelEntity("ftm", "p-1", "Person", null, documents, name));
        assertRejectsNull("documentIds", () -> new ModelEntity("ftm", "p-1", "Person", versions, null, name));
    }

    private static void assertRejectsNull(String field, Runnable construction) {
        assertThat(assertThrows(NullPointerException.class, construction::run).getMessage()).contains(field);
    }

    @Test
    public void test_refuses_statements_belonging_to_two_models() {
        String message = assertThrows(IllegalArgumentException.class, () ->
                ModelEntity.from(List.of(statement("name", "Jane Doe", "full_name"),
                        new Statement("id-2", "wikidata", "person-1", "Q5", "name", "Jane Q", null,
                                new Statement.Provenance("doc-1", "Sheet1", 12, "full_name"))),
                        Set.of("4.10.2"))).getMessage();

        assertThat(message).contains("ftm");
        assertThat(message).contains("wikidata");
    }

    @Test
    public void test_unions_the_document_ids_of_the_statements() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                Statement.of("ftm", "person-1", "Person", "birthDate", "1980-04-02",
                        new Statement.Provenance("doc-2", "Sheet1", 3, "dob"))), Set.of("4.10.2"));

        assertThat(entity.documentIds()).containsOnly("doc-1", "doc-2");
    }

    @Test
    public void test_the_same_document_seen_twice_is_one_document_id() {
        ModelEntity entity = ModelEntity.from(List.of(
                statement("name", "Jane Doe", "full_name"),
                statement("name", "J. Doe", "known_as")), Set.of("4.10.2"));

        assertThat(entity.documentIds()).containsOnly("doc-1");
    }

    @Test
    public void test_keeps_the_model_versions_it_is_given() {
        ModelEntity entity = ModelEntity.from(List.of(statement("name", "Jane Doe", "full_name")),
                Set.of("4.10.2", "4.11.0"));

        assertThat(entity.modelVersions()).containsOnly("4.10.2", "4.11.0");
    }

    private Statement statement(String property, String value, String column) {
        return Statement.of("ftm", "person-1", "Person", property, value,
                new Statement.Provenance("doc-1", "Sheet1", 12, column));
    }
}
