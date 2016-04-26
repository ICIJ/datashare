package org.icij.datashare.text.processing;

import java.util.Locale;

/**
 * Created by julien on 4/4/16.
 */
public enum NLPStage {
    SENTENCE,
    TOKEN,
    POS,
    LEMMA,
    NER,
    NONE;

    @Override
    public String toString() {
        return name().toLowerCase();
    }

    public static NLPStage parse(final String stage) throws IllegalArgumentException {
        if (stage== null || stage.isEmpty())
            return NONE;
        try {
            return valueOf(stage.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("\"%s\" is not a valid natural language processing stage.", stage));
        }
    }
}
