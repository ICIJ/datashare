package org.icij.datashare.text.nlp.ixapipe;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.NlpStage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.Language.*;

public class IxaPosModels extends IxaModels<eus.ixa.ixa.pipe.pos.Annotate> {
    private static volatile IxaPosModels instance;
    private static final Object mutex = new Object();
    static final Map<Language, String> MODEL_NAMES = new HashMap<Language, String>() {{
        put(ENGLISH, "en-pos-perceptron-autodict01-conll09.bin");
        put(SPANISH, "es-pos-perceptron-autodict01-ancora-2.0.bin");
        put(FRENCH, "fr-pos-perceptron-autodict01-sequoia.bin");
        put(GERMAN, "de-pos-perceptron-autodict01-conll09.bin");
        put(DUTCH, "nl-pos-perceptron-autodict01-alpino.bin");
        put(ITALIAN, "it-pos-perceptron-autodict01-ud.bin");
        put(BASQUE, "eu-pos-perceptron-ud.bin");
    }};
    private static Map<Language, String> lemmaModelNames = new HashMap<Language, String>() {{
        put(ENGLISH, "en-lemma-perceptron-conll09.bin");
        put(SPANISH, "es-lemma-perceptron-ancora-2.0..bin");
        put(FRENCH, "fr-lemma-perceptron-sequoia.bin");
        put(GERMAN, "de-lemma-perceptron-conll09.bin");
        put(DUTCH, "nl-lemma-perceptron-alpino.bin");
        put(ITALIAN, "it-lemma-perceptron-ud.bin");
        put(BASQUE, "eu-lemma-perceptron-ud.bin");
    }};

    public static final Map<Language, String> POS_TAGSET = new HashMap<Language, String>() {{
        put(ENGLISH, "PENN TREEBANK");
        put(SPANISH, "ANCORA");
        put(FRENCH, "CC");
        put(GERMAN, "STTS");
    }};

    public static IxaPosModels getInstance() {
        IxaPosModels local_instance = instance;
        if (local_instance == null) {
            synchronized (mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new IxaPosModels();
                }
            }
        }
        return instance;
    }

    private IxaPosModels() { super(NlpStage.POS);}

    @Override
    protected IxaAnnotate<eus.ixa.ixa.pipe.pos.Annotate> loadModelFile(Language language, ClassLoader loader) throws IOException {
        LOGGER.info("loading POS annotator for " + language);
        boolean dictag = false;
        boolean multiwords = false;
        if (asList(SPANISH, GALICIAN).contains(language)) {
            dictag = true;
            multiwords = true;
        }
        return new IxaAnnotate<>(new eus.ixa.ixa.pipe.pos.Annotate(
                posAnnotatorProperties(language, MODEL_NAMES.get(language),
                lemmaModelNames.get(language), multiwords, dictag))
        );
    }

    public Optional<String> getPosTagSet(Language language) {
        return Optional.of(POS_TAGSET.get(language));
    }

    private static Properties posAnnotatorProperties(Language language, String model,
                                                     String lemmatizerModel,
                                                     boolean multiwords,
                                                     boolean dictag) {
        final Properties annotateProperties = new Properties();
        annotateProperties.setProperty("models", model);
        annotateProperties.setProperty("lemmatizerModel", lemmatizerModel);
        annotateProperties.setProperty("language", String.valueOf(language));
        annotateProperties.setProperty("multiwords", String.valueOf(multiwords));
        annotateProperties.setProperty("dictag", String.valueOf(dictag));
        return annotateProperties;
    }

}
