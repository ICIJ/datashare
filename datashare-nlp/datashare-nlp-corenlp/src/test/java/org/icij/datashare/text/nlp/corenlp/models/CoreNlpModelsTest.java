package org.icij.datashare.text.nlp.corenlp.models;

import org.icij.datashare.text.Language;
import org.junit.Test;

import java.io.IOException;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.GERMAN;
import static org.icij.datashare.text.nlp.NlpStage.NER;

public class CoreNlpModelsTest {
    @Test
    public void testLoadJar() throws Exception {
        final CoreNlpModels jarModels = new CoreNlpModels(NER) {
            @Override
            String getPropertyName() { return null;}

            @Override
            protected Object loadModelFile(Language language, ClassLoader loader) throws IOException {
                return null;
            }
        };

        jarModels.addJarToContextClassLoader(GERMAN, getClass().getClassLoader());

        assertThat(getClass().getClassLoader().
                getResource("StanfordCoreNLP-german.properties")).isNotNull();
    }
}