package org.icij.datashare.text.nlp.corenlp.models;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.GERMAN;
import static org.icij.datashare.text.Language.ITALIAN;

public class CoreNlpModelsTest {
    @Test
    public void testLoadJar() {
        CoreNlpModels models = CoreNlpModels.getInstance();
        models.addResourceToContextClassLoader(models.getModelsBasePath(ITALIAN).resolve(models.getJarFileName(ITALIAN)));
        assertThat(ClassLoader.getSystemClassLoader().
                getResource("StanfordCoreNLP-italian.properties")).isNotNull();
    }
}
