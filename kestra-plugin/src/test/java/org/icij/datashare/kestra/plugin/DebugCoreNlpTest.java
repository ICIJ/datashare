package org.icij.datashare.kestra.plugin;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.nlp.corenlp.models.CoreNlpModels.SUPPORTED_LANGUAGES;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import org.apache.commons.io.FileUtils;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class DebugCoreNlpTest {
    private static final File distDir = new File("dist");

    @BeforeEach
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
    public void testLoadJar() throws InterruptedException {
        CoreNlpModels models = CoreNlpModels.getInstance();
        models.get(ENGLISH);
    }

    @Test
    public void test_should_load_language_specific_ner() throws Exception {
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
