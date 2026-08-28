package org.icij.datashare.model;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

public class StatementTest {
    private final Statement.Provenance provenance = new Statement.Provenance("doc-1", "Sheet1", 12, "full_name");

    @Test
    public void test_the_same_fact_gets_the_same_id() {
        Statement one = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe", provenance);
        Statement two = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe", provenance);

        assertThat(one.id()).isEqualTo(two.id());
        assertThat(one.id().length()).isEqualTo(96);
    }

    @Test
    public void test_the_id_changes_with_the_value() {
        Statement one = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe", provenance);
        Statement two = Statement.of("ftm", "person-1", "Person", "name", "Jane Doa", provenance);

        assertThat(one.id()).isNotEqualTo(two.id());
    }

    @Test
    public void test_the_id_changes_with_the_cell_it_came_from() {
        Statement one = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe", provenance);
        Statement two = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                new Statement.Provenance("doc-1", "Sheet1", 12, "legal_name"));
        Statement three = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                new Statement.Provenance("doc-1", "Sheet2", 12, "full_name"));
        Statement four = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                new Statement.Provenance("doc-1", "Sheet1", 13, "full_name"));

        assertThat(one.id()).isNotEqualTo(two.id());
        assertThat(one.id()).isNotEqualTo(three.id());
        assertThat(one.id()).isNotEqualTo(four.id());
    }

    @Test
    public void test_a_single_table_source_has_no_sheet() {
        Statement withNullSheet = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                new Statement.Provenance("doc-1", null, 1, "full_name"));
        Statement withEmptySheet = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                new Statement.Provenance("doc-1", "", 1, "full_name"));

        assertThat(withNullSheet.id()).isEqualTo(withEmptySheet.id());
    }

    @Test
    public void test_a_null_sheet_and_an_empty_sheet_are_the_same_provenance() {
        assertThat(new Statement.Provenance("doc-1", null, 1, "full_name"))
                .isEqualTo(new Statement.Provenance("doc-1", "", 1, "full_name"));
    }

    @Test
    public void test_a_nul_in_a_value_is_rejected() {
        try {
            Statement.of("ftm", "person-1", "Person", "name", "Jane\u0000doc-1", provenance);
            fail("should have rejected the NUL in the value");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("value");
        }
    }

    @Test
    public void test_a_nul_in_the_provenance_is_rejected() {
        try {
            new Statement.Provenance("doc-1\u0000", "Sheet1", 12, "full_name");
            fail("should have rejected the NUL in the documentId");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("documentId");
        }
    }

    @Test
    public void test_a_null_component_is_rejected_and_names_the_field() {
        assertRejectsNull("id", () -> new Statement(null, "ftm", "person-1", "Person", "name", "Jane Doe", provenance));
        assertRejectsNull("model", () -> new Statement("id", null, "person-1", "Person", "name", "Jane Doe", provenance));
        assertRejectsNull("entityId", () -> new Statement("id", "ftm", null, "Person", "name", "Jane Doe", provenance));
        assertRejectsNull("entityType", () -> new Statement("id", "ftm", "person-1", null, "name", "Jane Doe", provenance));
        assertRejectsNull("property", () -> new Statement("id", "ftm", "person-1", "Person", null, "Jane Doe", provenance));
        assertRejectsNull("value", () -> new Statement("id", "ftm", "person-1", "Person", "name", null, provenance));
        assertRejectsNull("provenance", () -> new Statement("id", "ftm", "person-1", "Person", "name", "Jane Doe", null));
        assertRejectsNull("documentId", () -> new Statement.Provenance(null, "Sheet1", 12, "full_name"));
        assertRejectsNull("column", () -> new Statement.Provenance("doc-1", "Sheet1", 12, null));
        assertRejectsNull("model", () -> Statement.of(null, "person-1", "Person", "name", "Jane Doe", provenance));
    }

    // Every component is guarded the same way, so the field the message names is the only thing worth
    // pinning: a copy-pasted guard naming its neighbour is what this catches.
    private static void assertRejectsNull(String field, Runnable construction) {
        assertThat(assertThrows(NullPointerException.class, construction::run).getMessage()).contains(field);
    }

    @Test
    public void test_an_unknown_model_is_rejected_before_the_statement_exists() {
        UnknownTargetModel thrown = assertThrows(UnknownTargetModel.class,
                () -> Statement.of("wikidata", "person-1", "Q5", "name", "Jane Doe", provenance));

        assertThat(thrown.name).isEqualTo("wikidata");
    }

    @Test
    public void test_an_entity_id_longer_than_the_indexed_column_is_rejected() {
        String tooLong = "x".repeat(Statement.MAX_ENTITY_ID_LENGTH + 1);

        assertThat(assertThrows(IllegalArgumentException.class,
                () -> Statement.of("ftm", tooLong, "Person", "name", "Jane Doe", provenance)).getMessage())
                .contains("entityId");
        assertThat(Statement.of("ftm", "x".repeat(Statement.MAX_ENTITY_ID_LENGTH), "Person", "name", "Jane Doe",
                provenance).entityId().length()).isEqualTo(Statement.MAX_ENTITY_ID_LENGTH);
    }

    @Test
    public void test_the_qualified_property_prefixes_the_model() {
        Statement statement = Statement.of("ftm", "person-1", "Person", "birthDate", "1980-04-02", provenance);

        assertThat(statement.qualifiedProperty()).isEqualTo("ftm:birthDate");
    }
}
