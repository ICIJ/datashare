package org.icij.datashare.text.nlp.corenlp;

import edu.stanford.nlp.util.PropertiesUtils;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpNerModels;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpPosModels;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.GERMAN;

public class CorenlpPipelineTest {

    @Test
    public void testInitialize_Ner() {
        CorenlpPipeline pipeline = new CorenlpPipeline(PropertiesUtils.fromString("stages=NER"));

        pipeline.initialize(GERMAN);

        assertThat(CoreNlpNerModels.getInstance().isLoaded(GERMAN)).isTrue();
        assertThat(CoreNlpPosModels.getInstance().isLoaded(GERMAN)).isFalse();
    }

    @Test
    public void testInitialize_Pos() {
        CorenlpPipeline pipeline = new CorenlpPipeline(PropertiesUtils.fromString("stages=POS"));

        pipeline.initialize(GERMAN);
        assertThat(CoreNlpPosModels.getInstance().isLoaded(GERMAN)).isTrue();
    }
}