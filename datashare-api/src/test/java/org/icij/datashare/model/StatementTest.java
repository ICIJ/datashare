package org.icij.datashare.model;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

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

        assertThat(one.id()).isNotEqualTo(two.id());
        assertThat(one.id()).isNotEqualTo(three.id());
    }

    @Test
    public void test_a_single_table_source_has_no_sheet() {
        Statement statement = Statement.of("ftm", "person-1", "Person", "name", "Jane Doe",
                new Statement.Provenance("doc-1", null, 1, "full_name"));

        assertThat(statement.id().length()).isEqualTo(96);
    }

    @Test
    public void test_the_qualified_property_prefixes_the_model() {
        Statement statement = Statement.of("ftm", "person-1", "Person", "birthDate", "1980-04-02", provenance);

        assertThat(statement.qualifiedProperty()).isEqualTo("ftm:birthDate");
    }
}
