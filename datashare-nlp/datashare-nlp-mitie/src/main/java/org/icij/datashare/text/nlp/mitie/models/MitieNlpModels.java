package org.icij.datashare.text.nlp.mitie.models;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.icij.datashare.text.Language;

import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.GERMAN;
import static org.icij.datashare.text.Language.SPANISH;
import org.icij.datashare.text.nlp.NlpStage;
import static org.icij.datashare.text.nlp.NlpStage.NER;
import static org.icij.datashare.text.nlp.NlpStage.SENTENCE;
import static org.icij.datashare.text.nlp.NlpStage.TOKEN;

/**
 * Created by julien on 9/19/16.
 */
public final class MitieNlpModels {

    // Base directory for OpenNLP models
    private static final Path BASE_DIR = Paths.get(
            System.getProperty("user.dir"), "src", "main", "resources",
            Paths.get( MitieNlpModels.class.getPackage().getName().replace(".", "/")).toString()
    );

    // Model paths
    public static final Map<NlpStage, HashMap<Language, Path>> PATH =
            new HashMap<NlpStage, HashMap<Language, Path>>(){{
                put(NER, new HashMap<Language, Path>() {{
                    put(ENGLISH, BASE_DIR.resolve(Paths.get(ENGLISH.name().toLowerCase(), "ner_model.dat")));
                    put(SPANISH, BASE_DIR.resolve(Paths.get(SPANISH.name().toLowerCase(), "ner_model.dat")));
                    put(GERMAN,  BASE_DIR.resolve(Paths.get(GERMAN.name().toLowerCase(),  "ner_model.dat")));
                }});
            }};

    public static final Map<NlpStage, Set<Language>> SUPPORTED_LANGUAGES =
            new HashMap<NlpStage, Set<Language>>(){{
                put(TOKEN,    PATH.get(NER).keySet());
                put(SENTENCE, PATH.get(NER).keySet());
                put(NER,      PATH.get(NER).keySet());
            }};


}
