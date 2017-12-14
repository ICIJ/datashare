package org.icij.datashare.text.nlp.corenlp.annotators;

import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.ling.CoreLabel;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpModels;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.icij.datashare.text.nlp.NlpStage.NER;


/**
 * Stanford CoreNLP Named entities recognizers
 *
 * Created by julien on 8/31/16.
 */
public enum CoreNlpNerAnnotator {
    INSTANCE;

    private static final Log LOGGER = LogFactory.getLog(CoreNlpNerAnnotator.class);


    // Annotators (Conditional Random Fields Classifier)
    private final Map<Language, AbstractSequenceClassifier<CoreLabel>> annotator;

    // Annotator locks
    private final ConcurrentHashMap<Language, Lock> annotatorLock;


    CoreNlpNerAnnotator() {
        annotator = new HashMap<>();
        annotatorLock = new ConcurrentHashMap<Language, Lock>(){{
            CoreNlpModels.SUPPORTED_LANGUAGES.get(NER)
                    .forEach( lang -> put(lang, new ReentrantLock()));
        }};
    }


    /**
     * Lock and get PoS tagger for language
     *
     * @param language the annotator language
     * @return an Optional of MaxenTagger if successfully (loaded and) retrieved; empty Optional otherwise
     */
    public Optional<AbstractSequenceClassifier<CoreLabel>> get(Language language)  {
        CoreNlpModels.sharedModels.apply(NER, language)
                .forEach( lang -> annotatorLock.get(lang).lock() );
        try {
            if ( ! load(language)) {
                return Optional.empty();
            }
            return Optional.of(annotator.get(language));
        } finally {
            CoreNlpModels.sharedModels.apply(NER, language)
                    .forEach( lang -> annotatorLock.get(lang).unlock() );
        }
    }

    /**
     * Load and store annotator for language
     *
     * @param language the model language to load
     * @return true if successfully loaded; false otherwise
     */
    public boolean load(Language language) {
        if (annotator.containsKey(language)) {
            return true;
        }
        try {
            LOGGER.info("loading NER annotator for " + language);
            String modelPath = CoreNlpModels.PATH.get(NER).get(language).toString();
            CRFClassifier<CoreLabel> classifier = CRFClassifier.getClassifier(modelPath);
            CoreNlpModels.sharedModels.apply(NER, language)
                    .forEach( lang -> annotator.put(language, classifier) );
            return true;
        } catch (ClassNotFoundException | IOException e) {
            LOGGER.error("failed loading NER annotator", e);
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
}
