package org.icij.datashare.text.nlp.mitie.annotators;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.mit.ll.mitie.EntityMentionVector;
import edu.mit.ll.mitie.NamedEntityExtractor;
import edu.mit.ll.mitie.StringVector;
import edu.mit.ll.mitie.TokenIndexVector;

import org.icij.datashare.text.Language;
import static org.icij.datashare.text.nlp.NlpStage.NER;
import static org.icij.datashare.text.nlp.mitie.models.MitieNlpModels.SUPPORTED_LANGUAGES;
import org.icij.datashare.text.nlp.mitie.models.MitieNlpModels;

/**
 * Created by julien on 9/19/16.
 */
public enum MitieNlpNerAnnotator {
    INSTANCE;

    private static final Logger LOGGER = LogManager.getLogger(MitieNlpNerAnnotator.class);


    // Annotators (Conditional Random Fields Classifier)
    private final Map<Language, NamedEntityExtractor> annotator;

    // Annotator locks
    private final ConcurrentHashMap<Language, Lock> annotatorLock;

    // Annotator tag set
    private final HashMap<Language, StringVector> tagSet;


    MitieNlpNerAnnotator() {
        annotator = new HashMap<>();
        annotatorLock = new ConcurrentHashMap<Language, Lock>(){{
            SUPPORTED_LANGUAGES.get(NER)
                    .forEach( lang ->
                            put(lang, new ReentrantLock())
                    );
        }};
        tagSet = new HashMap<>();
    }


    public EntityMentionVector apply(TokenIndexVector tokens, Language language) {
        annotatorLock.get(language).lock();
        try {
            Optional<NamedEntityExtractor> annotator = get(language);
            if (! annotator.isPresent()) {
                return new EntityMentionVector();
            }
            LOGGER.info("Name-finding - " + Thread.currentThread().getName());
            return annotator.get().extractEntities(tokens);
        }  finally {
            annotatorLock.get(language).unlock();
        }
    }


    /**
     * Lock and get PoS tagger for language
     *
     * @param language the annotator language
     * @return an Optional of MaxenTagger if successfully (loaded and) retrieved; empty Optional otherwise
     */
    private Optional<NamedEntityExtractor> get(Language language)  {
        try {
            if ( ! load(language)) {
                return Optional.empty();
            }
            return Optional.of(annotator.get(language));
        } catch (Exception e) {
            LOGGER.error("Failed to extract entities", e);
            return Optional.empty();
        }
    }

    /**
     * Load and store annotator for language
     *
     * @param language the model language to load
     * @return true if successfully loaded; false otherwise
     */
    private boolean load(Language language) {
        if (annotator.containsKey(language)) {
            return true;
        }
        LOGGER.info("Loading NER annotator for " + language + " - " + Thread.currentThread().getName());
        try {
            String modelPath = MitieNlpModels.PATH.get(NER).get(language).toString();
            NamedEntityExtractor classifier = new NamedEntityExtractor(modelPath);
            annotator.put(language, classifier);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to load NER annotator", e);
            return false;
        }
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

    public StringVector getTagSet(Language language) {
        if (tagSet.containsKey(language)) {
            return tagSet.get(language);
        }
        if (annotator.containsKey(language)) {
            tagSet.put(language, annotator.get(language).getPossibleNerTags());
            return tagSet.get(language);
        }
        return new StringVector();
    }

}
