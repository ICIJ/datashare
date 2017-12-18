package org.icij.datashare.text.nlp.corenlp.annotators;

import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.POS;


/**
 * Stanford CoreNLP Part-of-Speech taggers
 *
 * Tagsets
 *
 * ENGLISH: PENN TREEBANK
 * https://www.ling.upenn.edu/courses/Fall_2003/ling001/penn_treebank_pos.html
 * http://www.cs.upc.edu/~nlp/SVMTool/PennTreebank.html
 *
 * FRENCH CC:
 * http://french-postaggers.tiddlyspot.com/
 *
 * SPANISH: (reduced) ANCORA
 * http://nlp.stanford.edu/software/spanish-faq.shtml#tagset
 * https://web.archive.org/web/20160325024315/http://nlp.lsi.upc.edu/freeling/doc/tagsets/tagset-es.html
 * http://stackoverflow.com/questions/27047450/meaning-of-stanford-spanish-pos-tagger-tags
 *
 * GERMAN: STTS
 * http://www.ims.uni-stuttgart.de/forschung/ressourcen/lexika/TagSets/stts-table.html
 *
 * Created by julien on 8/31/16.
 */
public enum CoreNlpPosAnnotator {
    INSTANCE;

    static final Logger LOGGER = LoggerFactory.getLogger(CoreNlpPosAnnotator.class);

    public static final Map<Language, String> POS_TAGSET = new HashMap<Language, String>() {{
        put(ENGLISH, "PENN TREEBANK");
        put(SPANISH, "ANCORA");
        put(FRENCH,  "CC");
        put(GERMAN,  "STTS");
    }};


    // Annotators (Maximum Entropy Tagger)
    private final Map<Language, MaxentTagger> annotator;

    // Annotator locks
    private final ConcurrentHashMap<Language, Lock> annotatorLock;


    CoreNlpPosAnnotator() {
        annotator = new HashMap<>();
        annotatorLock = new ConcurrentHashMap<Language, Lock>(){{
            CoreNlpModels.SUPPORTED_LANGUAGES.get(POS).forEach( lang -> put(lang, new ReentrantLock()) );
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
            LOGGER.info(getClass().getName() + " Loading POS annotator for " + language);
            String modelPath = CoreNlpModels.PATH.get(POS).get(language).toString();
            annotator.put(language, new MaxentTagger(modelPath));

        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " Failed to load POS annotator", e);
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

