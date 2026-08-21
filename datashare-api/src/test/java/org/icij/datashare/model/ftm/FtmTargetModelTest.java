package org.icij.datashare.model.ftm;

import org.icij.datashare.model.EntityType;
import org.icij.datashare.model.Property;
import org.icij.datashare.model.TargetModel;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

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
}
