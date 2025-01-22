package org.icij.datashare.text.nlp.corenlp.models;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.GERMAN;

public class CoreNlpModelsTest {
    @Test
    public void testLoadJar() {
        CoreNlpModels models = CoreNlpModels.getInstance();
        models.addResourceToContextClassLoader(models.getModelsBasePath(GERMAN).resolve(models.getJarFileName(GERMAN)));

        assertThat(ClassLoader.getSystemClassLoader().
                getResource("StanfordCoreNLP-german.properties")).isNotNull();
    }
}
