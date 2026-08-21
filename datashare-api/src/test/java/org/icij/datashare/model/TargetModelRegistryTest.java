package org.icij.datashare.model;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;

public class TargetModelRegistryTest {
    @Test
    public void test_resolves_the_ftm_model() {
        assertThat(TargetModelRegistry.get("ftm").name()).isEqualTo("ftm");
    }

    @Test
    public void test_parses_the_model_once() {
        assertThat(TargetModelRegistry.get("ftm")).isSameAs(TargetModelRegistry.get("ftm"));
    }

    @Test
    public void test_an_unknown_model_names_the_known_ones() {
        try {
            TargetModelRegistry.get("wikidata");
            fail("should have refused an unregistered model");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("wikidata");
            assertThat(e.getMessage()).contains("ftm");
        }
    }

    @Test
    public void test_a_null_name_is_rejected() {
        try {
            TargetModelRegistry.get(null);
            fail("should have rejected a null name");
        } catch (NullPointerException e) {
            assertThat(e.getMessage()).contains("name");
        }
    }
}
