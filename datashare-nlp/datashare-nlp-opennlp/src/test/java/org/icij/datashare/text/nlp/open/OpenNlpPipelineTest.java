package org.icij.datashare.text.nlp.open;

import org.icij.datashare.text.Language;
import org.junit.Before;
import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.nlp.NlpPipeline.Property.STAGES;

public class OpenNlpPipelineTest {
    private OpenNlpPipeline openNlpPipeline;

    @Before
    public void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty(STAGES.getName(), "SENTENCE,TOKEN");
        openNlpPipeline = new OpenNlpPipeline(props);
    }

    @Test
    public void test_initialize() {
        openNlpPipeline.initialize(Language.FRENCH);

        assertThat(openNlpPipeline.sentencer.keySet()).contains(Language.FRENCH);
        assertThat(openNlpPipeline.tokenizer.keySet()).contains(Language.FRENCH);
        assertThat(openNlpPipeline.nerFinder.keySet()).excludes(Language.FRENCH);
    }
}