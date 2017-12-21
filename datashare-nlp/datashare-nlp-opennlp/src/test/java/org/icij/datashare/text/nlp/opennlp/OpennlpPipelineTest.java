package org.icij.datashare.text.nlp.opennlp;

import org.icij.datashare.text.Language;
import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.nlp.Pipeline.Property.STAGES;

public class OpennlpPipelineTest {

    @Test
    public void test_initialize() {
        Properties props = new Properties();
        props.setProperty(STAGES.getName(), "SENTENCE,TOKEN");
        System.out.println("before instantiation");
        OpennlpPipeline openNlpPipeline = new OpennlpPipeline(props);
        System.out.println("after instantiation " + openNlpPipeline);
        openNlpPipeline.initialize(Language.FRENCH);

        assertThat(openNlpPipeline.sentencer.keySet()).contains(Language.FRENCH);
        assertThat(openNlpPipeline.tokenizer.keySet()).contains(Language.FRENCH);
        assertThat(openNlpPipeline.nerFinder.keySet()).excludes(Language.FRENCH);
    }
}