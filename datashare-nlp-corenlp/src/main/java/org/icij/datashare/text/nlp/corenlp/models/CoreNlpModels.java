package org.icij.datashare.text.nlp.corenlp.models;

import static org.icij.datashare.text.Language.CHINESE;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Language.GERMAN;
import static org.icij.datashare.text.Language.HUNGARIAN;
import static org.icij.datashare.text.Language.ITALIAN;
import static org.icij.datashare.text.Language.SPANISH;

import edu.stanford.nlp.pipeline.LanguageInfo;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.Pipeline;

public class CoreNlpModels extends AbstractModels<StanfordCoreNLP> {
    static final String VERSION = "4.5.10";

    public static final Set<Language> SUPPORTED_LANGUAGES = Set.of(
        ENGLISH,
        SPANISH,
        FRENCH,
        CHINESE,
        GERMAN,
        HUNGARIAN,
        ITALIAN
    );

    private static volatile CoreNlpModels instance;
    private static final Object mutex = new Object();

    @Override
    protected StanfordCoreNLP loadModelFile(Language language) throws IOException {
        LOGGER.info("loading pipeline Annotator for " + language);
        Path modelFilePath = getModelFilePath(language);
        super.addResourceToContextClassLoader(modelFilePath);
        // Load base model
        String propertyFileName = LanguageInfo.getLanguagePropertiesFile(language.name().toLowerCase());
        Properties properties = new Properties();
        properties.load(ClassLoader.getSystemClassLoader().getResourceAsStream(propertyFileName));
        // Override some props
        properties.setProperty("ner.useSUTime", "false");
        properties.setProperty("ner.applyNumericClassifiers", "false");
        // Without numeric classifier, pos and lemma are not needed, additionally since 4.5, sentence split is included
        // in the tokenize step
        properties.setProperty("annotators", "tokenize,ner");
        properties.setProperty("ner.applyFineGrained", "false");

        return new StanfordCoreNLP(properties, true);
    }

    public static CoreNlpModels getInstance() {
        CoreNlpModels local_instance = instance;
        if (local_instance == null) {
            synchronized (mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new CoreNlpModels();
                }
            }
        }
        return instance;
    }

    @Override
    public Path getModelsBasePath(Language language) {
        if (!Language.ENGLISH.equals(language)) {
            return super.getModelsBasePath(language);
        }
        return super.getModelsBasePath(language).getParent();
    }

    private Path getModelFilePath(Language language) {
        return getModelsBasePath(language).resolve(getJarFileName(language));
    }

    String getJarFileName(Language language) {
        List<String> filename;
        if (language.equals(ENGLISH)) {
            filename = List.of("stanford", "corenlp", getVersion(), "models.jar");
        } else {
            filename = List.of("stanford", "corenlp", "models", language.name().toLowerCase() + ".jar");
        }
        return String.join("-", filename);
    }

    private CoreNlpModels() {
        super(Pipeline.Type.CORENLP);
    }

    @Override
    protected String getVersion() {
        return VERSION;
    }
}
