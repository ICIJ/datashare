package org.icij.datashare.model.ftm;

import org.icij.datashare.model.EntityType;
import org.icij.datashare.model.ModelEntity;
import org.icij.datashare.model.Property;
import org.icij.datashare.model.TargetModel;
import org.icij.datashare.model.UnreadableModelResource;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;

public class FtmTargetModelTest {
    private final TargetModel model = new FtmTargetModel();

    @Test
    public void test_loads_the_bundled_model() {
        assertThat(model.name()).isEqualTo("ftm");
        assertThat(model.version()).isEqualTo("4.10.2");
    }

    @Test
    public void test_looks_a_type_up_by_name() {
        assertThat(model.type("Person").isPresent()).isTrue();
        assertThat(model.type("Persona").isPresent()).isFalse();
    }

    @Test
    public void test_an_inherited_property_resolves_from_the_type_that_declares_it() {
        Property name = model.property("Person", "name").get();

        assertThat(name.qname()).isEqualTo("Thing:name");
        assertThat(name.type()).isEqualTo("name");
    }

    @Test
    public void test_knows_which_types_are_abstract() {
        assertThat(model.type("Thing").get().isAbstract()).isTrue();
        assertThat(model.type("Interest").get().isAbstract()).isTrue();
        assertThat(model.type("LegalEntity").get().isAbstract()).isFalse();
    }

    @Test
    public void test_an_edge_type_carries_its_ends() {
        EntityType.Edge edge = model.type("Employment").get().edge();

        assertThat(edge.source()).isEqualTo("employee");
        assertThat(edge.target()).isEqualTo("employer");
        assertThat(edge.directed()).isTrue();
        assertThat(model.type("Person").get().edge()).isNull();
    }

    @Test
    public void test_required_is_taken_verbatim_rather_than_inherited() {
        assertThat(model.type("Person").get().required()).containsOnly("name");
        assertThat(model.type("Address").get().required()).isEmpty();
        assertThat(model.type("Employment").get().required()).containsOnly("employee", "employer");
    }

    @Test
    public void test_ancestors_include_the_type_itself() {
        assertThat(model.type("Person").get().ancestors()).containsOnly("Person", "LegalEntity", "Thing");
    }

    @Test
    public void test_a_reverse_relation_is_flagged_as_a_stub() {
        Property employers = model.property("Person", "employers").get();

        assertThat(employers.stub()).isTrue();
        assertThat(employers.range()).isEqualTo("Employment");
        assertThat(model.property("Person", "birthDate").get().stub()).isFalse();
    }

    @Test
    public void test_an_unknown_property_or_type_is_empty() {
        assertThat(model.property("Person", "shoeSize").isPresent()).isFalse();
        assertThat(model.property("Persona", "name").isPresent()).isFalse();
    }

    @Test
    public void test_a_property_two_ancestors_declare_resolves_to_the_last_ancestor_by_name() {
        assertThat(model.property("Message", "date").get().qname()).isEqualTo("Interval:date");
        assertThat(model.property("Message", "description").get().qname()).isEqualTo("Thing:description");
        assertThat(model.property("Event", "namesMentioned").get().qname()).isEqualTo("Interval:namesMentioned");
    }

    @Test
    public void test_a_stub_property_another_of_the_types_declares_as_written_is_no_violation() {
        List<TargetModel.Violation> violations = model.validate(new ModelEntity("x-1",
                Set.of("LegalEntity", "ContractAward"),
                Map.of("name", List.of("Total"), "callForTenders", List.of("c-1"))));

        assertThat(violations.stream().anyMatch(violation -> violation.message().contains("stub"))).isFalse();
    }

    @Test
    public void test_the_missing_required_properties_are_reported_in_the_model_s_order() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("e-1", Set.of("Employment"), Map.of()));

        assertThat(violations.get(0).message()).isEqualTo("type 'Employment' requires 'employer'");
    }

    @Test
    public void test_validating_a_company_with_no_properties_reports_the_missing_inherited_name() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("c-1", Set.of("Company"), Map.of()));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("Company");
        assertThat(violations.get(0).message()).contains("name");
    }

    @Test
    public void test_validating_an_interest_reports_that_the_type_is_abstract() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("i-1", Set.of("Interest"), Map.of()));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("Interest");
        assertThat(violations.get(0).message()).contains("abstract");
    }

    @Test
    public void test_validating_a_person_writing_the_stub_property_employers_reports_the_stub() {
        List<TargetModel.Violation> violations = model.validate(new ModelEntity("p-1", Set.of("Person"),
                Map.of("name", List.of("Jane Doe"), "employers", List.of("e-1"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("employers");
        assertThat(violations.get(0).message()).contains("stub");
    }
}
