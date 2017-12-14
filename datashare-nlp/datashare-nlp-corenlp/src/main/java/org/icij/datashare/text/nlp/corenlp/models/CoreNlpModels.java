package org.icij.datashare.text.nlp.corenlp.models;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.*;
import org.icij.datashare.text.nlp.NlpStage;
import static org.icij.datashare.text.nlp.NlpStage.*;


/**
 * Stanford CoreNLP Models Information
 *
 * Created by julien on 8/31/16.
 */
public final class CoreNlpModels {

    // Models base directory (within jar)
    private static final String BASE_DIR = "edu/stanford/nlp/models";

    // Model directories
    private static final Map<NlpStage, Path> DIRECTORY =
            new HashMap<NlpStage, Path>() {{
                put(POS, Paths.get(BASE_DIR, "pos-tagger") );
                put(NER, Paths.get(BASE_DIR, "ner") );
            }};

    // Model paths
    public static final Map<NlpStage, HashMap<Language, Path>> PATH =
            new HashMap<NlpStage, HashMap<Language, Path>>(){{
                put(POS, new HashMap<Language, Path>() {{
                    put(ENGLISH, DIRECTORY.get(POS).resolve(Paths.get("english-left3words", "english-left3words-distsim.tagger")));
                    put(SPANISH, DIRECTORY.get(POS).resolve(Paths.get("spanish",            "spanish-distsim.tagger")));
                    put(FRENCH,  DIRECTORY.get(POS).resolve(Paths.get("french",             "french.tagger")));
                    put(GERMAN,  DIRECTORY.get(POS).resolve(Paths.get("german",             "german-hgc.tagger")));
                }});
                put(NER, new HashMap<Language, Path>() {{
                    put(ENGLISH, DIRECTORY.get(NER).resolve("english.all.3class.distsim.crf.ser.gz"));
                    put(SPANISH, DIRECTORY.get(NER).resolve("spanish.ancora.distsim.s512.crf.ser.gz"));
                    put(FRENCH,  DIRECTORY.get(NER).resolve("english.all.3class.distsim.crf.ser.gz"));
                    put(GERMAN,  DIRECTORY.get(NER).resolve("german.conll.hgc_175m_600.crf.ser.gz" ));
                }});
            }};

    // Model path property keys
    public static final HashMap<NlpStage, String> PROPERTY_KEY =
            new HashMap<NlpStage, String>() {{
                put(POS, "pos.model");
                put(NER, "ner.model");
            }};

    public static final Map<NlpStage, Set<Set<Language>>> SHARED_MODELS =
            new HashMap<NlpStage, Set<Set<Language>>>() {{
                put(NER, new HashSet<Set<Language>>() {{
                    add( new HashSet<>( asList(ENGLISH, FRENCH)) );
                    add( new HashSet<>( singletonList(SPANISH)) );
                    add( new HashSet<>( singletonList(GERMAN)) );
                }});
            }};

    public static BiFunction<NlpStage, Language, Set<Language>> sharedModels =
            (stage, language) ->
                    SHARED_MODELS.get(stage).stream()
                            .filter( sharedSet -> sharedSet.contains(language) )
                            .flatMap( Set::stream )
                            .collect(Collectors.toSet());

    public static final Map<NlpStage, Set<Language>> SUPPORTED_LANGUAGES =
            new HashMap<NlpStage, Set<Language>>() {{
                put(TOKEN,    PATH.get(POS).keySet());
                put(SENTENCE, PATH.get(POS).keySet());
                put(LEMMA,    PATH.get(POS).keySet());
                put(POS,      PATH.get(POS).keySet());
                put(NER,      PATH.get(NER).keySet());
            }};

}
