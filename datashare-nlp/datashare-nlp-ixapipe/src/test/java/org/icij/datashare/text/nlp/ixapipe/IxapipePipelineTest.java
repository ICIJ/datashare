package org.icij.datashare.text.nlp.ixapipe;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.junit.Before;
import org.junit.Test;

import java.util.Properties;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.ITALIAN;
import static org.icij.datashare.text.nlp.Pipeline.Property.STAGES;

public class IxapipePipelineTest {
    private IxapipePipeline ixapipePipeline;
    @Before
    public void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty(STAGES.getName(), "POS,NER");
        AbstractModels.syncModels(false);
        ixapipePipeline = new IxapipePipeline(new PropertiesProvider(props));
    }

    @Test
    public void test_initialize() throws InterruptedException {
        ixapipePipeline.initialize(ITALIAN);

        assertThat(IxaPosModels.getInstance().isLoaded(ITALIAN)).isEqualTo(true);
        assertThat(IxaNerModels.getInstance().isLoaded(ITALIAN)).isEqualTo(true);
    }

    @Test
    public void test_process() throws Exception {
        ixapipePipeline.initialize(ITALIAN);
        Annotations annotations = ixapipePipeline.process("italiano contenuto de Firenze", "docId", ITALIAN);

        assertThat(annotations).isNotNull();
        assertThat(annotations.get(NlpStage.TOKEN).size()).isEqualTo(4);
        assertThat(annotations.get(NlpStage.NER).size()).isEqualTo(1);
    }
}