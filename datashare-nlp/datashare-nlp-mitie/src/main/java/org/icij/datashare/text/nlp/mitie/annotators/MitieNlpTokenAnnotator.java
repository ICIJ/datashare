package org.icij.datashare.text.nlp.mitie.annotators;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.mit.ll.mitie.global;
import edu.mit.ll.mitie.TokenIndexVector;


/**
 * Created by julien on 9/19/16.
 */
public enum MitieNlpTokenAnnotator {
    INSTANCE;

    private static final Logger LOGGER = LogManager.getLogger(MitieNlpTokenAnnotator.class);


    /**
     * Lock and execute tokenizer
     *
     * @return an Optional of MaxenTagger if successfully (loaded and) retrieved; empty Optional otherwise
     */
    public synchronized TokenIndexVector apply(String input)  {
        LOGGER.info("Tokenizing - " + Thread.currentThread().getName());
        try {
            return global.tokenizeWithOffsets(input);
        } catch (Exception e) {
            LOGGER.error("Failed to tokenize input ", e);
            return new TokenIndexVector();
        }
    }

}
