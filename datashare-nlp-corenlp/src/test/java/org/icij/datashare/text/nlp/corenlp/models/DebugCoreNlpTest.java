package org.icij.datashare.text.nlp.corenlp.models;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.FRENCH;

import java.io.File;
import java.net.MalformedURLException;
import org.icij.datashare.DynamicClassLoader;
import org.junit.Test;


public class DebugCoreNlpTest {
    @Test
    public void testLoadJar() throws InterruptedException, MalformedURLException {
        DynamicClassLoader systemClassLoader = (DynamicClassLoader)ClassLoader.getSystemClassLoader();
        File distDir = new File("dist");
        assertThat(distDir).exists();
        systemClassLoader.add(distDir.toURI().toURL());
        CoreNlpModels models = CoreNlpModels.getInstance();
        models.get(FRENCH);
    }
}
