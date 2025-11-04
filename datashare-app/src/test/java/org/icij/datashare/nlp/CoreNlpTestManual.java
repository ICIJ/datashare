package org.icij.datashare.nlp;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.apache.commons.io.FileUtils;
import org.icij.datashare.DynamicClassLoader;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.corenlp.CorenlpPipeline;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpModels;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.text.nlp.corenlp.models.CoreNlpModels.SUPPORTED_LANGUAGES;

// this test is not executed by CI because it doesn't end with "Test"
// its goal is to test manually the core NLP pipeline
// it has not been automated because it loads near 1GB models and is flaky
@Ignore
public class CoreNlpTestManual {
    private static final File distDir = new File("dist");

    @Before
    public void setUp() throws IOException {
        assertThat(distDir).exists();
        for (File file : Objects.requireNonNull(distDir.listFiles())) {
            if (file.isDirectory()) {
                FileUtils.deleteDirectory(file);
            } else {
                boolean deleted = file.delete();
                assert deleted;
            }
        }
    }

    @Test
    public void test_download_and_load_jar() throws Exception {
        DynamicClassLoader systemClassLoader = (DynamicClassLoader) ClassLoader.getSystemClassLoader();
        assertThat(distDir).exists();
        systemClassLoader.add(distDir.toURI().toURL());
        CorenlpPipeline corenlpPipeline = new CorenlpPipeline(new PropertiesProvider());
        corenlpPipeline.initialize(Language.ENGLISH);
        List<NamedEntity> process =
            corenlpPipeline.process(DocumentBuilder.createDoc("my_doc_id").with("this is Dwight's document").build());
        assertThat(process.size()).isGreaterThan(0);
    }

    @Test
    public void test_download_and_load_jar_for_french() throws Exception {
        DynamicClassLoader systemClassLoader = (DynamicClassLoader) ClassLoader.getSystemClassLoader();
        assertThat(distDir).exists();
        systemClassLoader.add(distDir.toURI().toURL());
        CorenlpPipeline corenlpPipeline = new CorenlpPipeline(new PropertiesProvider());
        corenlpPipeline.initialize(Language.FRENCH);
        List<NamedEntity> process =
            corenlpPipeline.process(DocumentBuilder.createDoc("my_doc_id").with("C'est un document à Jean.").build());
        assertThat(process.size()).isGreaterThan(0);
    }

    @Test
    public void test_should_load_language_specific_ner() throws Exception {
        DynamicClassLoader systemClassLoader = (DynamicClassLoader) ClassLoader.getSystemClassLoader();
        assertThat(distDir).exists();
        systemClassLoader.add(distDir.toURI().toURL());
        CoreNlpModels.getInstance().get(Language.ENGLISH); // Test EN loading
        SUPPORTED_LANGUAGES.stream().sorted().filter(l -> !l.equals(Language.ENGLISH))
            .forEach(rethrowConsumer(language -> {
                StanfordCoreNLP loadedModel = CoreNlpModels.getInstance().get(language);
                String modelName = Arrays.stream(((String) loadedModel.getProperties().get("ner.model")).split("/"))
                    .reduce((a, b) -> b).orElse("");
                assertThat(modelName).contains(language.name().toLowerCase());
            }));
    }
}
