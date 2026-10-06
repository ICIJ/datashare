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
        assertThat(model.validate(new ModelEntity("fake", "p-1", "Person", Set.of(), Set.of(),
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
        List<String> violations = model.validate(
                new ModelEntity("fake", "p-1", "Robot", Set.of(), Set.of(), Map.of("name", List.of("Jane Doe"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("Robot");
        assertThat(violations.get(0)).contains("fake");
    }

    @Test
    public void test_an_abstract_type_cannot_be_instantiated() {
        List<String> violations = model.validate(
                new ModelEntity("fake", "t-1", "Thing", Set.of(), Set.of(), Map.of("name", List.of("Jane Doe"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("Thing");
        assertThat(violations.get(0)).contains("abstract");
    }

    @Test
    public void test_an_undeclared_property_is_a_violation() {
        List<String> violations = model.validate(new ModelEntity("fake", "p-1", "Person", Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe"), "shoeSize", List.of("42"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("shoeSize");
    }

    @Test
    public void test_a_stub_property_cannot_be_written() {
        List<String> violations = model.validate(new ModelEntity("fake", "p-1", "Person", Set.of(), Set.of(),
                Map.of("name", List.of("Jane Doe"), "employers", List.of("e-1"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("employers");
        assertThat(violations.get(0)).contains("stub");
    }

    @Test
    public void test_a_missing_required_property_is_a_violation() {
        List<String> violations = model.validate(
                new ModelEntity("fake", "p-1", "Person", Set.of(), Set.of(), Map.of()));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("Person");
        assertThat(violations.get(0)).contains("name");
    }

    @Test
    public void test_a_blank_required_property_is_a_violation() {
        List<String> violations = model.validate(
                new ModelEntity("fake", "p-1", "Person", Set.of(), Set.of(), Map.of("name", List.of(" "))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("name");
    }

    @Test
    public void test_an_edge_needs_both_of_its_ends() {
        List<String> violations = model.validate(new ModelEntity("fake", "e-1", "Employment", Set.of(), Set.of(),
                Map.of("employee", List.of("p-1"))));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("Employment");
        assertThat(violations.get(0)).contains("employer");
    }

    @Test
    public void test_a_shape_mapping_every_required_property_has_no_violation() {
        assertThat(model.validateShape("Person", Set.of("name"))).isEmpty();
    }

    @Test
    public void test_a_shape_missing_a_required_property_is_a_violation() {
        List<String> violations = model.validateShape("Person", Set.of());

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).isEqualTo("type 'Person' requires 'name'");
    }

    @Test
    public void test_a_shape_missing_an_edge_end_is_a_violation() {
        List<String> violations = model.validateShape("Employment", Set.of("employee"));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).isEqualTo("edge type 'Employment' needs 'employer'");
    }

    @Test
    public void test_a_shape_with_a_stub_or_undeclared_property_is_a_violation() {
        List<String> violations = model.validateShape("Person", Set.of("name", "employers", "shoeSize"));

        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).contains("employers").contains("stub");
        assertThat(violations.get(1)).contains("shoeSize");
    }

    @Test
    public void test_a_shape_of_an_unknown_or_abstract_type_is_a_violation() {
        assertThat(model.validateShape("Robot", Set.of("name")).get(0)).contains("unknown type 'Robot'");
        assertThat(model.validateShape("Thing", Set.of("name")).get(0)).contains("abstract");
    }

    private static class FakeModel implements TargetModel {
        private static final Property VALUE = new Property(null, false);
        private static final Property STUB = new Property("Employment", true);
        private static final Map<String, EntityType> TYPES = Map.of(
                "Thing", new EntityType("Thing", true, Set.of("Thing"),
                        Map.of("name", VALUE), Set.of("name"), null),
                "Person", new EntityType("Person", false, Set.of("Person", "Thing"),
                        Map.of("name", VALUE, "employers", STUB),
                        Set.of("name"), null),
                "Company", new EntityType("Company", false, Set.of("Company", "Thing"),
                        Map.of("name", VALUE, "vatNumber", VALUE),
                        Set.of("name"), null),
                "Employment", new EntityType("Employment", false, Set.of("Employment"),
                        Map.of("employee", VALUE, "employer", VALUE),
                        Set.of("employee"), new EntityType.Edge("employee", "employer")));

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
            return new Property(null, false);
        }

        private static Property stub(String name) {
            return new Property("Employment", true);
        }
    }
}
