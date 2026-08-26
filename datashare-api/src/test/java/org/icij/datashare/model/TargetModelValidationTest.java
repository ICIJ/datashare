package org.icij.datashare.model;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;

public class TargetModelValidationTest {
    private final TargetModel model = new FakeModel();

    @Test
    public void test_a_valid_entity_has_no_violation() {
        assertThat(model.validate(new ModelEntity("fake", "p-1", Set.of("Person"), Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe"))))).isEmpty();
    }

    @Test
    public void test_a_property_declared_by_an_ancestor_resolves() {
        assertThat(model.property("Person", "name").isPresent()).isTrue();
        assertThat(model.property("Person", "nope").isPresent()).isFalse();
        assertThat(model.property("Nope", "name").isPresent()).isFalse();
    }

    @Test
    public void test_an_unknown_type_is_a_violation() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("fake", "p-1", Set.of("Robot"), Set.of(), Set.of(), Map.of("name", List.of("Jane Doe"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("Robot");
        assertThat(violations.get(0).message()).contains("fake");
    }

    @Test
    public void test_an_abstract_type_cannot_be_instantiated() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("fake", "t-1", Set.of("Thing"), Set.of(), Set.of(), Map.of("name", List.of("Jane Doe"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("Thing");
        assertThat(violations.get(0).message()).contains("abstract");
    }

    @Test
    public void test_an_undeclared_property_is_a_violation() {
        List<TargetModel.Violation> violations = model.validate(new ModelEntity("fake", "p-1", Set.of("Person"), Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe"), "shoeSize", List.of("42"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("shoeSize");
    }

    @Test
    public void test_a_stub_property_cannot_be_written() {
        List<TargetModel.Violation> violations = model.validate(new ModelEntity("fake", "p-1", Set.of("Person"), Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe"), "employers", List.of("e-1"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("employers");
        assertThat(violations.get(0).message()).contains("stub");
    }

    @Test
    public void test_a_missing_required_property_is_a_violation() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("fake", "p-1", Set.of("Person"), Set.of(), Set.of(), Map.of()));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("Person");
        assertThat(violations.get(0).message()).contains("name");
    }

    @Test
    public void test_a_blank_required_property_is_a_violation() {
        List<TargetModel.Violation> violations = model.validate(
                new ModelEntity("fake", "p-1", Set.of("Person"), Set.of(), Set.of(), Map.of("name", List.of(" "))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("name");
    }

    @Test
    public void test_an_edge_needs_both_of_its_ends() {
        List<TargetModel.Violation> violations = model.validate(new ModelEntity("fake", "e-1", Set.of("Employment"), Set.of(), Set.of(),
                Map.of("employee", List.of("p-1"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("Employment");
        assertThat(violations.get(0).message()).contains("employer");
    }

    @Test
    public void test_a_multi_type_entity_may_use_a_property_of_either_type() {
        assertThat(model.validate(new ModelEntity("fake", "p-1", Set.of("Person", "Company"), Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe"), "vatNumber", List.of("FR123"))))).isEmpty();
    }

    @Test
    public void test_a_concrete_type_makes_its_abstract_ancestor_instantiable() {
        assertThat(model.validate(new ModelEntity("fake", "p-1", Set.of("Person", "Thing"), Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe"))))).isEmpty();
    }

    @Test
    public void test_an_undeclared_property_names_the_types_in_a_stable_order() {
        List<TargetModel.Violation> violations = model.validate(new ModelEntity("fake", "p-1", Set.of("Person", "Company"), Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe"), "shoeSize", List.of("42"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("[Company, Person]");
    }

    private static class FakeModel implements TargetModel {
        private static final Map<String, EntityType> TYPES = Map.of(
                "Thing", new EntityType("Thing", true, Set.of("Thing"),
                        Map.of("name", property("name")), Set.of("name"), null),
                "Person", new EntityType("Person", false, Set.of("Person", "Thing"),
                        Map.of("name", property("name"), "employers", stub("employers")),
                        Set.of("name"), null),
                "Company", new EntityType("Company", false, Set.of("Company", "Thing"),
                        Map.of("name", property("name"), "vatNumber", property("vatNumber")),
                        Set.of("name"), null),
                "Employment", new EntityType("Employment", false, Set.of("Employment"),
                        Map.of("employee", property("employee"), "employer", property("employer")),
                        Set.of("employee"), new EntityType.Edge("employee", "employer", true)));

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public Optional<EntityType> type(String name) {
            return Optional.ofNullable(TYPES.get(name));
        }

        @Override
        public String serialize(ModelEntity entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelEntity parse(String json) {
            throw new UnsupportedOperationException();
        }

        private static Property property(String name) {
            return new Property("Fake:" + name, null, false);
        }

        private static Property stub(String name) {
            return new Property("Fake:" + name, "Employment", true);
        }
    }
}
