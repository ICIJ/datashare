package org.icij.datashare.model;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

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
    public void test_an_unknown_model_names_the_known_ones_and_keeps_the_requested_name() {
        UnknownTargetModel e = assertThrows(UnknownTargetModel.class, () -> TargetModelRegistry.get("wikidata"));

        assertThat(e.getMessage()).contains("wikidata");
        assertThat(e.getMessage()).contains("ftm");
        assertThat(e.name).isEqualTo("wikidata");
    }

    @Test
    public void test_a_null_name_is_rejected() {
        assertThat(assertThrows(NullPointerException.class, () -> TargetModelRegistry.get(null)).getMessage())
                .contains("name");
    }
}
