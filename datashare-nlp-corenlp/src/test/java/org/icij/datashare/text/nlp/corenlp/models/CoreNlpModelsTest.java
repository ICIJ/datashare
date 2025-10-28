package org.icij.datashare.text.nlp.corenlp.models;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.ITALIAN;

import org.junit.Test;

public class CoreNlpModelsTest {
    @Test
    public void testLoadJar() {
        CoreNlpModels models = CoreNlpModels.getInstance();
        models.addResourceToContextClassLoader(
            models.getModelsBasePath(ITALIAN).resolve(models.getJarFileName(ITALIAN)));
        assertThat(ClassLoader.getSystemClassLoader().
            getResource("StanfordCoreNLP-italian.properties")).isNotNull();
    }
}
