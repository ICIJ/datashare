package org.icij.datashare.text.nlp.mitie.annotators;

import edu.mit.ll.mitie.TokenIndexVector;
import edu.mit.ll.mitie.global;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 * Created by julien on 9/19/16.
 */
public enum MitieNlpTokenAnnotator {
    INSTANCE;

    final Log LOGGER = LogFactory.getLog(getClass());


    /**
     * Lock and apply Tokenizer
     *
     * @return an Optional of MaxenTagger if successfully (loaded and) retrieved; empty Optional otherwise
     */
    public synchronized TokenIndexVector apply(String input)  {
        try {
            return global.tokenizeWithOffsets(input);
        } catch (Exception e) {
            LOGGER.error("failed tokenizing input ", e);
            return new TokenIndexVector();
        }
    }

}
