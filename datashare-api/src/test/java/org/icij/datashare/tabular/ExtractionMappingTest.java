package org.icij.datashare.tabular;

import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.UnknownTargetModel;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class ExtractionMappingTest {

    private static ExtractionMapping mapping(String model, Map<String, ExtractionMapping.EntityMapping> entities) {
        return new ExtractionMapping("map-1", "prj", "jdoe", "members", model, "doc-1",
                RowSourceOptions.defaults(), entities);
    }

    private static ExtractionMapping.EntityMapping person(Map<String, ExtractionMapping.PropertyMapping> properties) {
        return new ExtractionMapping.EntityMapping("Person", List.of("id"), properties);
    }

    private static ExtractionMapping.PropertyMapping column(String name) {
        return new ExtractionMapping.PropertyMapping(List.of(name), null, null, null, null);
    }

    @Test
    public void test_unknown_model_is_rejected_at_construction() {
        UnknownTargetModel thrown = assertThrows(UnknownTargetModel.class,
                () -> mapping("wikidata", Map.of("member", person(Map.of("name", column("full_name"))))));
        assertThat(thrown.name).isEqualTo("wikidata");
    }

    @Test
    public void test_a_valid_mapping_reports_no_violation() {
        assertThat(mapping("ftm", Map.of("member", person(Map.of("name", column("full_name"))))).validate()).isEmpty();
    }

    @Test
    public void test_validate_reports_an_unknown_entity_type() {
        ExtractionMapping.EntityMapping unknown =
                new ExtractionMapping.EntityMapping("Unicorn", List.of("id"), Map.of("name", column("n")));
        assertThat(mapping("ftm", Map.of("member", unknown)).validate().toString()).contains("Unicorn");
    }

    @Test
    public void test_validate_reports_an_unknown_property_on_a_known_type() {
        assertThat(mapping("ftm", Map.of("member", person(Map.of("hoofSize", column("h"))))).validate().toString())
                .contains("hoofSize");
    }

    @Test
    public void test_validate_reports_an_abstract_type() {
        ExtractionMapping.EntityMapping thing =
                new ExtractionMapping.EntityMapping("Thing", List.of("id"), Map.of("name", column("n")));
        assertThat(mapping("ftm", Map.of("member", thing)).validate().toString()).contains("Thing");
    }

    @Test
    public void test_validate_reports_a_stub_property() {
        assertThat(mapping("ftm", Map.of("member", person(Map.of("eventsOrganized", column("e")))))
                .validate().toString()).contains("eventsOrganized");
    }

    @Test
    public void test_validate_reports_a_dangling_entity_alias() {
        ExtractionMapping.PropertyMapping reference =
                new ExtractionMapping.PropertyMapping(List.of(), null, null, "typo-alias", null);
        assertThat(mapping("ftm", Map.of("member", person(Map.of("name", reference)))).validate().toString())
                .contains("typo-alias");
    }

    @Test
    public void test_property_needs_exactly_one_source() {
        assertThrows(InvalidPropertyMapping.class,
                () -> new ExtractionMapping.PropertyMapping(List.of(), null, null, null, null));
        assertThrows(InvalidPropertyMapping.class,
                () -> new ExtractionMapping.PropertyMapping(List.of("a"), null, "SS", null, null));
    }

    @Test
    public void test_join_needs_more_than_one_column() {
        assertThrows(InvalidPropertyMapping.class,
                () -> new ExtractionMapping.PropertyMapping(List.of("a"), " ", null, null, null));
    }

    @Test
    public void test_format_needs_columns() {
        assertThrows(InvalidPropertyMapping.class,
                () -> new ExtractionMapping.PropertyMapping(List.of(), null, "SS", null, "%d.%m.%Y"));
    }

    @Test
    public void test_a_null_user_is_allowed() {
        ExtractionMapping cliAuthored = new ExtractionMapping("map-1", "prj", null, "members", "ftm", "doc-1",
                RowSourceOptions.defaults(), Map.of("member", person(Map.of("name", column("full_name")))));
        assertThat(cliAuthored.userId()).isNull();
    }

    @Test
    public void test_a_mapping_needs_at_least_one_entity() {
        assertThrows(EmptyExtractionMapping.class, () -> mapping("ftm", Map.of()));
    }

    @Test
    public void test_an_entity_needs_at_least_one_key() {
        assertThrows(InvalidEntityMapping.class,
                () -> new ExtractionMapping.EntityMapping("Person", List.of(), Map.of()));
    }

    @Test
    public void test_validate_reports_a_required_property_the_mapping_never_fills() {
        assertThat(mapping("ftm", Map.of("member", person(Map.of("birthDate", column("dob")))))
                .validate().toString()).contains("requires 'name'");
    }

    @Test
    public void test_validate_reports_the_unmapped_ends_of_an_edge_type() {
        ExtractionMapping.EntityMapping ownership =
                new ExtractionMapping.EntityMapping("Ownership", List.of("id"), Map.of("percentage", column("pct")));
        String violations = mapping("ftm", Map.of("stake", ownership)).validate().toString();
        assertThat(violations).contains("owner").contains("asset");
    }

    @Test
    public void test_validate_reports_its_violations_in_a_stable_order() {
        ExtractionMapping.EntityMapping unicorn =
                new ExtractionMapping.EntityMapping("Unicorn", List.of("id"), Map.of("name", column("n")));
        ExtractionMapping.EntityMapping dragon =
                new ExtractionMapping.EntityMapping("Dragon", List.of("id"), Map.of("name", column("n")));
        List<TargetModel.Violation> violations =
                mapping("ftm", Map.of("zebra", unicorn, "aardvark", dragon)).validate();

        assertThat(violations.get(0).message()).contains("aardvark");
        assertThat(violations.get(1).message()).contains("zebra");
    }

    @Test
    public void test_absent_reader_options_fall_back_to_the_defaults() {
        ExtractionMapping mapping = new ExtractionMapping("map-1", "prj", "jdoe", "members", "ftm", "doc-1",
                null, Map.of("member", person(Map.of("name", column("full_name")))));
        assertThat(mapping.options()).isEqualTo(RowSourceOptions.defaults());
    }

    @Test
    public void test_entity_reference_needs_no_column() {
        ExtractionMapping.PropertyMapping reference =
                new ExtractionMapping.PropertyMapping(List.of(), null, null, "member", null);
        assertThat(reference.entity()).isEqualTo("member");
    }
}
