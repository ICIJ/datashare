package org.icij.datashare.text.nlp.opennlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.nlp.AbstractPipeline.NLP_STAGES_PROP;

public class OpennlpPipelineTest {

    @Test
    public void test_initialize() throws InterruptedException {
        AbstractModels.syncModels(false);
        Properties props = new Properties();
        props.setProperty(NLP_STAGES_PROP, "SENTENCE,TOKEN");
        OpennlpPipeline openNlpPipeline = new OpennlpPipeline(new PropertiesProvider(props));
        openNlpPipeline.initialize(Language.FRENCH);

        assertThat(openNlpPipeline.sentencer.keySet()).contains(Language.FRENCH);
        assertThat(openNlpPipeline.tokenizer.keySet()).contains(Language.FRENCH);
        assertThat(openNlpPipeline.nerFinder.keySet()).excludes(Language.FRENCH);
    }
}