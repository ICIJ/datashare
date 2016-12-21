package org.icij.datashare.text.nlp.core.annotators;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.stanford.nlp.tagger.maxent.MaxentTagger;

import org.icij.datashare.text.Language;
import static org.icij.datashare.text.nlp.NlpStage.POS;
import org.icij.datashare.text.nlp.core.models.CoreNlpModels;


/**
 * Stanford CoreNLP Part-of-Speech taggers
 *
 * Created by julien on 8/31/16.
 */
public enum CoreNlpPosAnnotator {
    INSTANCE;

    private static final Logger LOGGER = LogManager.getLogger(CoreNlpPosAnnotator.class);

    public static final String POS_TAGSET = "Penn Treebank";


    // Annotators (Maximum Entropy Tagger)
    private final Map<Language, MaxentTagger> annotator;

    // Annotator locks
    private final ConcurrentHashMap<Language, Lock> annotatorLock;


    CoreNlpPosAnnotator() {
        annotator = new HashMap<>();
        annotatorLock = new ConcurrentHashMap<Language, Lock>(){{
            CoreNlpModels.SUPPORTED_LANGUAGES.get(POS).stream()
                    .forEach( l -> put(l, new ReentrantLock()) );
        }};
    }

    /**
     * Lock and get PoS tagger for language
     *
     * @param language the annotator language
     * @return an Optional of MaxenTagger if successfully (loaded and) retrieved; empty Optional otherwise
     */
    public Optional<MaxentTagger> get(Language language)  {
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

    /**
     * Load and store Part-of-Speech annotator for language
     *
     * @param language the model language to load
     * @return true if loaded successfully; false otherwise
     */
    public boolean load(Language language) {
        if ( annotator.containsKey(language) && annotator.get(language) != null ) {
            return true;
        }
        try {
            LOGGER.info("Loading POS annotator for " + language + " - " + Thread.currentThread().getName());
            String modelPath = CoreNlpModels.PATH.get(POS).get(language).toString();
            annotator.put(language, new MaxentTagger(modelPath));

        } catch (Exception e) {
            LOGGER.error("Failed to load POS annotator", e);
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

