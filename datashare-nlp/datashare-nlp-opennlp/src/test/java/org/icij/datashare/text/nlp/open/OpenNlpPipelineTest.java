package org.icij.datashare.text.nlp.open;

import org.icij.datashare.text.Language;
import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;

public class OpenNlpPipelineTest {
    private final OpenNlpPipeline openNlpPipeline = new OpenNlpPipeline(new Properties());

    @Test
    public void test_initialize() {
        openNlpPipeline.initialize(Language.FRENCH);

        assertThat(openNlpPipeline.nerFinder.keySet()).contains(Language.FRENCH);
    }
}