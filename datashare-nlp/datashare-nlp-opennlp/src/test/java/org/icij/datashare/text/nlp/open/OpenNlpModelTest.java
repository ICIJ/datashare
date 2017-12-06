package org.icij.datashare.text.nlp.open;

import org.junit.Test;

import java.util.Locale;

import static org.fest.assertions.Assertions.assertThat;

public class OpenNlpModelTest {
    @Test
    public void test_acceptance_load_nlp_types() throws Exception {
        OpenNlpModel frModel = new OpenNlpModel(Locale.FRANCE);

        assertThat(frModel.components).isNotEmpty();
        assertThat(frModel.components).hasSize(3);
        assertThat(frModel.isLoaded()).isFalse();
    }
}