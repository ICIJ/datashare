package org.icij.datashare.text.nlp.corenlp.models;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.Language.CHINESE;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Language.GERMAN;
import static org.icij.datashare.text.Language.HUNGARIAN;
import static org.icij.datashare.text.Language.ITALIAN;
import static org.icij.datashare.text.Language.SPANISH;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;

import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.Pipeline;

public class CoreNlpModels extends AbstractModels<StanfordCoreNLP> {
    static final String VERSION = "4.5.8";
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
    protected StanfordCoreNLP loadModelFile(Language language) throws InterruptedException {
        LOGGER.info("loading pipeline Annotator for {}", language);
        Properties properties = new Properties();
        properties.setProperty("ner.useSUTime", "false");
        properties.setProperty("ner.applyNumericClassifiers", "false");
        properties.setProperty("annotators", "tokenize,ner");
        properties.setProperty("tokenize.language", language.iso6391Code());
        properties.setProperty("ner.applyFineGrained", "false");

        if (language != ENGLISH) {
            get(ENGLISH);
        }
        properties.setProperty("ner.model", "edu/stanford/nlp/models/ner/english.all.3class.caseless.distsim.crf.ser.gz");
        super.addResourceToContextClassLoader(getModelFilePath(language));
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

    private Path getModelFilePath(Language language) {
        return getModelsBasePath(language).resolve(getJarFileName(language));
    }

    String getJarFileName(Language language) {
        return String.join("-", asList("stanford", "corenlp", getVersion(), "models", language.name().toLowerCase() + ".jar"));
    }

    private CoreNlpModels() {
        super(Pipeline.Type.CORENLP);
    }

    @Override
    protected String getVersion() {
        return VERSION;
    }
}
