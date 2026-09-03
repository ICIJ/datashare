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

    private static ExtractionMapping.PropertyMapping reference(String alias) {
        return new ExtractionMapping.PropertyMapping(List.of(), null, null, alias, null);
    }

    @Test
    public void test_validate_reports_the_flaws_only_a_run_would_otherwise_catch() {
        ExtractionMapping.PropertyMapping blank =
                new ExtractionMapping.PropertyMapping(List.of(), null, " ", null, null);
        ExtractionMapping.PropertyMapping twoDigitYear =
                new ExtractionMapping.PropertyMapping(List.of("born"), null, null, null, "dd/MM/yy");

        String violations = mapping("ftm", Map.of("member",
                person(Map.of("nationality", blank, "birthDate", twoDigitYear)))).validate().toString();

        assertThat(violations).contains("blank literal");
        assertThat(violations).contains("two-digit year");
    }

    @Test
    public void test_validate_reports_every_nul_a_statement_would_carry() {
        ExtractionMapping.PropertyMapping nulLiteral =
                new ExtractionMapping.PropertyMapping(List.of(), null, "f\u0000r", null, null);
        ExtractionMapping nulled = new ExtractionMapping("map-1", "prj", "jdoe", "members", "ftm",
                "doc\u00001", RowSourceOptions.defaults(),
                Map.of("member", person(Map.of("nationality", nulLiteral))));

        String violations = nulled.validate().toString();

        assertThat(violations).contains("document id");
        assertThat(violations).contains("literal holding a NUL");
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
        assertThat(mapping("ftm", Map.of("member", person(Map.of("name", reference("typo-alias")))))
                .validate().toString()).contains("typo-alias");
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
    public void test_validate_reports_a_required_property_the_mapping_never_fills() {
        assertThat(mapping("ftm", Map.of("member", person(Map.of("birthDate", column("dob")))))
                .validate().toString()).contains("requires 'name'");
    }

    // Debt requires only its source end (debtor), so its target end (creditor) is reported by the
    // edge rule alone: on an edge type whose both ends are required, the required-property rule
    // reports them first and the edge rule never runs.
    @Test
    public void test_validate_reports_the_unmapped_ends_of_an_edge_type() {
        ExtractionMapping.EntityMapping debt =
                new ExtractionMapping.EntityMapping("Debt", List.of("id"), Map.of("amount", column("amt")));
        String violations = mapping("ftm", Map.of("owed", debt)).validate().toString();
        assertThat(violations).contains("edge type 'Debt' needs 'creditor'");
    }

    @Test
    public void test_validate_reports_a_reference_to_the_wrong_kind_of_entity() {
        ExtractionMapping.EntityMapping ownership = new ExtractionMapping.EntityMapping("Ownership",
                List.of("id"), Map.of("owner", reference("home"), "asset", column("a")));
        ExtractionMapping.EntityMapping address =
                new ExtractionMapping.EntityMapping("Address", List.of("id"), Map.of("city", column("c")));
        String violations = mapping("ftm", Map.of("stake", ownership, "home", address)).validate().toString();
        assertThat(violations).contains("needs a 'LegalEntity', but entity 'home' is a 'Address'");
    }

    @Test
    public void test_validate_accepts_a_reference_to_a_descendant_of_the_declared_range() {
        ExtractionMapping.EntityMapping ownership = new ExtractionMapping.EntityMapping("Ownership",
                List.of("id"), Map.of("owner", reference("boss"), "asset", column("a")));
        ExtractionMapping.EntityMapping person = new ExtractionMapping.EntityMapping("Person",
                List.of("id"), Map.of("name", column("n")));
        String violations = mapping("ftm", Map.of("stake", ownership, "boss", person)).validate().toString();
        assertThat(violations).excludes("needs a");
    }

    @Test
    public void test_validate_reports_a_reference_on_a_property_that_holds_a_value() {
        ExtractionMapping.EntityMapping person = new ExtractionMapping.EntityMapping("Person",
                List.of("id"), Map.of("name", reference("member")));
        String violations = mapping("ftm", Map.of("member", person)).validate().toString();
        assertThat(violations).contains("holds a value, not a reference to an entity");
    }

    @Test
    public void test_validate_reports_a_reference_on_an_undeclared_property_once() {
        ExtractionMapping.EntityMapping person = new ExtractionMapping.EntityMapping("Person",
                List.of("id"), Map.of("name", column("n"), "sidekick", reference("member")));
        String violations = mapping("ftm", Map.of("member", person)).validate().toString();

        assertThat(violations).contains("no property 'sidekick'");
        assertThat(violations).excludes("holds a value");
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
        assertThat(reference("member").entity()).isEqualTo("member");
    }
    @Test
    public void test_an_entity_without_keys_is_valid() {
        assertThat(mapping("ftm", Map.of("member", new ExtractionMapping.EntityMapping("Person",
                List.of(), Map.of("name", column("full_name"))))).validate()).isEmpty();
    }
}
