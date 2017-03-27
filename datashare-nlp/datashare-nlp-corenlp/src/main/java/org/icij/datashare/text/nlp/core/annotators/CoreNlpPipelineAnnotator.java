package org.icij.datashare.text.nlp.core.annotators;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import static java.util.Arrays.asList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;

import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.*;
import org.icij.datashare.text.nlp.NlpStage;
import static org.icij.datashare.text.nlp.NlpStage.*;
import org.icij.datashare.text.nlp.core.models.CoreNlpModels;


/**
 * Stanford CoreNLP full integrated pipeline (TOKEN, SENTENCE, LEMMA, POS, NER)
 *
 * Created by julien on 8/31/16.
 */
public enum CoreNlpPipelineAnnotator {
    INSTANCE;

    private static final Logger LOGGER = LogManager.getLogger(CoreNlpPipelineAnnotator.class);

    // Supported stages for each language
    public static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>(){{
                put(ENGLISH, new HashSet<>(asList(SENTENCE, TOKEN, POS, LEMMA, NER)));
                put(SPANISH, new HashSet<>(asList(SENTENCE, TOKEN, POS, LEMMA, NER)));
                put(FRENCH,  new HashSet<>(asList(SENTENCE, TOKEN, POS, LEMMA, NER)));
                put(GERMAN,  new HashSet<>(asList(SENTENCE, TOKEN, POS, LEMMA, NER)));
            }};

    // NlpStage to CoreNLP annotator names
    private static final Map<NlpStage, String> CORE_STAGE_NAME =
            new HashMap<NlpStage, String>(){{
                put(SENTENCE, "ssplit");
                put(TOKEN,    "tokenize");
                put(LEMMA,    "lemma");
                put(POS,      "pos");
                put(NER,      "ner");
            }};

    /**
     * @return the list of Stanford CoreNLP annotator names from {@code stages}
     */
    private static List<String> CORE_STAGE_NAMES(List<NlpStage> stages) {
        return stages
                .stream()
                .map(CORE_STAGE_NAME::get)
                .collect(Collectors.toList());
    }


    // Annotators
    private final Map<Language, StanfordCoreNLP> annotator;

    // Annotator locks
    private final ConcurrentHashMap<Language, Lock> annotatorLock;


    CoreNlpPipelineAnnotator() {
        annotator = new HashMap<>();
        annotatorLock = new ConcurrentHashMap<Language, Lock>(){{
            CoreNlpModels.SUPPORTED_LANGUAGES.get(NER)
                    .forEach( lang -> put(lang, new ReentrantLock()) );
        }};
    }


    public Optional<StanfordCoreNLP> get(Language language)  {
        Lock l = annotatorLock.get(language);
        l.lock();
        try {
            if ( ! load(language)) {
                return Optional.empty();
            }
            return Optional.of(annotator.get(language));
        } finally {
            l.unlock();
        }
    }

    public boolean load(Language language) {
        List<NlpStage> stages = new ArrayList<>(SUPPORTED_STAGES.get(language));
        return load(language, stages);
    }

    public boolean load(Language language, List<NlpStage> stages) {
        if ( annotator.containsKey(language) && annotator.get(language) != null ) {
            return true;
        }
        LOGGER.info(getClass().getName() + " - LOADING " + stages.toString() + " PIPELINE Annotator for " + language);
        Properties properties = new Properties();
        properties.setProperty("annotators", String.join(", ", CORE_STAGE_NAMES(stages)));
        properties.setProperty("ner.useSUTime", "false");
        properties.setProperty("ner.applyNumericClassifiers", "false");
        CoreNlpModels.PATH.keySet().stream()
                .filter ( stages::contains )
                .forEach( stage ->
                        properties.setProperty(
                                CoreNlpModels.PROPERTY_KEY.get(stage),
                                CoreNlpModels.PATH        .get(stage).get(language).toString()
                        )
                );
        try {
            annotator.put(language, new StanfordCoreNLP(properties, true));

        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " - FAILED LOADING PIPELINE annotator", e);
            return false;
        }
        return true;
    }

    public void unload(Language language) {
        Lock l = annotatorLock.get(language);
        l.lock();
        try {
            annotator.remove(language);
        } finally {
            l.unlock();
        }
    }

}
