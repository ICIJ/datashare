package org.icij.datashare.text.nlp.corenlp.models;

import org.icij.datashare.text.Language;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.GERMAN;
import static org.icij.datashare.text.nlp.NlpStage.NER;

public class CoreNlpModelsTest {
    @Test
    public void testLoadJar() {
        final CoreNlpModels jarModels = new CoreNlpModels(NER) {
            @Override
            String getPropertyName() { return null;}

            @Override
            protected Object loadModelFile(Language language) {
                return null;
            }
        };

        jarModels.addResourceToContextClassLoader(
                jarModels.getModelsBasePath(GERMAN).resolve(jarModels.getJarFileName(GERMAN)));

        assertThat(ClassLoader.getSystemClassLoader().
                getResource("StanfordCoreNLP-german.properties")).isNotNull();
    }
}
