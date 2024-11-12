package org.icij.datashare.text.nlp.corenlp.models;

import static org.icij.datashare.text.Language.CHINESE;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Language.GERMAN;
import static org.icij.datashare.text.Language.HUNGARIAN;
import static org.icij.datashare.text.Language.ITALIAN;
import static org.icij.datashare.text.Language.SPANISH;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import java.util.Properties;
import java.util.Set;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.Pipeline;

public class CoreNlpPipelineModels extends AbstractModels<StanfordCoreNLP> {
    public static final Set<Language> SUPPORTED_LANGUAGES = Set.of(
        ENGLISH,
        SPANISH,
        FRENCH,
        CHINESE,
        GERMAN,
        HUNGARIAN,
        ITALIAN
    );
    private static volatile CoreNlpPipelineModels instance;
    private static final Object mutex = new Object();

    @Override
    protected StanfordCoreNLP loadModelFile(Language language) {
        LOGGER.info("loading pipeline Annotator for " + language);
        Properties properties = new Properties();
        properties.setProperty("ner.useSUTime", "false");
        properties.setProperty("ner.applyNumericClassifiers", "false");
        // Without numeric classifier, pos and lemma are not needed, additionally since 4.5, sentence split is included
        // in the tokenize step
        properties.setProperty("annotators", "tokenize,ner");
        properties.setProperty("tokenize.language", language.iso6391Code());

        return new StanfordCoreNLP(properties, true);
    }

    public static CoreNlpPipelineModels getInstance() {
        CoreNlpPipelineModels local_instance = instance;
        if (local_instance == null) {
            synchronized (mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new CoreNlpPipelineModels();
                }
            }
        }
        return instance;
    }

    private CoreNlpPipelineModels() {
        super(Pipeline.Type.CORENLP);
    }

    @Override
    protected String getVersion() {
        return CoreNlpModels.VERSION;
    }
}
