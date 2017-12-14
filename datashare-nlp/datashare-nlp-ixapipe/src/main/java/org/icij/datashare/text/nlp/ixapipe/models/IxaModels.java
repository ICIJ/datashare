package org.icij.datashare.text.nlp.ixapipe.models;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.*;
import org.icij.datashare.text.nlp.NlpStage;
import static org.icij.datashare.text.nlp.NlpStage.*;


/**
 * Ixa-Pipe Tokenization, Part-of-Speech and Named Entity Recognition Models handling singleton
 *
 * Created by julien on 9/23/16.
 */
public final class IxaModels {

    // Base directory
    private static final Path BASE_DIR = Paths.get(
            System.getProperty("user.dir"), "src", "main", "resources",
            Paths.get( IxaModels.class.getPackage().getName().replace(".", "/") ).toString()
    );

    // Sub-directory <stage/language>
    private static final BiFunction<Language, NlpStage, String> SUB_DIR = (language, stage) ->
            Paths.get(stage.name().toLowerCase(), language.toString()).toString();

    // Model paths
    public static final Map<NlpStage, HashMap<Language, Path>> PATH =
            new HashMap<NlpStage, HashMap<Language, Path>>() {{
                put(NER, new HashMap<Language, Path>() {{
                    put(ENGLISH, BASE_DIR.resolve(Paths.get(SUB_DIR.apply(ENGLISH, NER), "conll03", "en-best-clusters-conll03.bin")));
                    put(SPANISH, BASE_DIR.resolve(Paths.get(SUB_DIR.apply(SPANISH, NER),            "es-clusters-dictlbj-conll02.bin")));
                    put(GERMAN,  BASE_DIR.resolve(Paths.get(SUB_DIR.apply(GERMAN,  NER),            "de-clusters-dictlbj-conll03.bin")));
                    put(DUTCH,   BASE_DIR.resolve(Paths.get(SUB_DIR.apply(DUTCH,   NER),            "nl-clusters-dictlbj-conll02.bin")));
                    put(ITALIAN, BASE_DIR.resolve(Paths.get(SUB_DIR.apply(ITALIAN, NER),            "it-clusters-evalita09.bin")));
                    put(BASQUE,  BASE_DIR.resolve(Paths.get(SUB_DIR.apply(BASQUE,  NER),            "eu-clusters-egunkaria.bin")));
                }});
                put(POS, new HashMap<Language, Path>() {{
                    put(ENGLISH, BASE_DIR.resolve(Paths.get(SUB_DIR.apply(ENGLISH, POS), "en-pos-perceptron-autodict01-conll09.bin")));
                    put(SPANISH, BASE_DIR.resolve(Paths.get(SUB_DIR.apply(SPANISH, POS), "es-pos-perceptron-autodict01-ancora-2.0.bin")));
                    put(FRENCH,  BASE_DIR.resolve(Paths.get(SUB_DIR.apply(FRENCH,  POS), "fr-pos-perceptron-autodict01-sequoia.bin")));
                    put(GERMAN,  BASE_DIR.resolve(Paths.get(SUB_DIR.apply(GERMAN,  POS), "de-pos-perceptron-autodict01-conll09.bin")));
                    put(DUTCH,   BASE_DIR.resolve(Paths.get(SUB_DIR.apply(DUTCH,   POS), "nl-pos-perceptron-autodict01-alpino.bin")));
                    put(ITALIAN, BASE_DIR.resolve(Paths.get(SUB_DIR.apply(ITALIAN, POS), "it-pos-perceptron-autodict01-ud.bin")));
                    put(BASQUE,  BASE_DIR.resolve(Paths.get(SUB_DIR.apply(BASQUE,  POS), "eu-pos-perceptron-ud.bin")));
                }});
                put(LEMMA, new HashMap<Language, Path>() {{
                    put(ENGLISH, BASE_DIR.resolve(Paths.get(SUB_DIR.apply(ENGLISH, LEMMA), "en-lemma-perceptron-conll09.bin")));
                    put(SPANISH, BASE_DIR.resolve(Paths.get(SUB_DIR.apply(SPANISH, LEMMA), "es-lemma-perceptron-ancora-2.0..bin")));
                    put(FRENCH,  BASE_DIR.resolve(Paths.get(SUB_DIR.apply(FRENCH,  LEMMA), "fr-lemma-perceptron-sequoia.bin")));
                    put(GERMAN,  BASE_DIR.resolve(Paths.get(SUB_DIR.apply(GERMAN,  LEMMA), "de-lemma-perceptron-conll09.bin")));
                    put(DUTCH,   BASE_DIR.resolve(Paths.get(SUB_DIR.apply(DUTCH,   LEMMA), "nl-lemma-perceptron-alpino.bin")));
                    put(ITALIAN, BASE_DIR.resolve(Paths.get(SUB_DIR.apply(ITALIAN, LEMMA), "it-lemma-perceptron-ud.bin")));
                    put(BASQUE,  BASE_DIR.resolve(Paths.get(SUB_DIR.apply(BASQUE,  POS),   "eu-lemma-perceptron-ud.bin")));
                }});
            }};

    public static final Map<NlpStage, Set<Language>> SUPPORTED_LANGUAGES =
            new HashMap<NlpStage, Set<Language>>(){{
                put(TOKEN, PATH.get(POS).keySet());
                put(POS,   PATH.get(POS).keySet());
                put(LEMMA, PATH.get(LEMMA).keySet());
                put(NER,   PATH.get(NER).keySet());
            }};

    // Part-of-speech refence tag set
    public static final Map<Language, String> POS_TAGSET = new HashMap<Language, String>() {{
        put(ENGLISH, "PENN TREEBANK");
        put(SPANISH, "ANCORA");
        put(FRENCH,  "CC");
        put(GERMAN,  "STTS");
    }};

}
