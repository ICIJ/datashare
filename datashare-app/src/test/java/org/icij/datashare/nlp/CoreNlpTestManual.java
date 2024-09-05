package org.icij.datashare.nlp;

import org.icij.datashare.DynamicClassLoader;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.corenlp.CorenlpPipeline;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class CoreNlpTestManual {
    @Test
    public void test_download_and_load_jar() throws Exception {
        DynamicClassLoader systemClassLoader = (DynamicClassLoader)ClassLoader.getSystemClassLoader();
        File distDir = new File("dist");
        assertThat(distDir).exists();
        systemClassLoader.add(distDir.toURI().toURL());
        CorenlpPipeline corenlpPipeline = new CorenlpPipeline(new PropertiesProvider());
        corenlpPipeline.initialize(Language.ENGLISH);
        List<NamedEntity> process = corenlpPipeline.processDoc(DocumentBuilder.createDoc("my_doc_id").with("this is Dwight's document").build());
        assertThat(process.size()).isGreaterThan(0);
    }
}
