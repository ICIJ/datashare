package org.icij.datashare.text.processing;

import java.security.cert.PKIXRevocationChecker;
import java.util.Locale;
import java.util.Optional;

/**
 * Created by julien on 4/4/16.
 */
public enum NLPStage {
    SENTENCE,
    TOKEN,
    POS,
    LEMMA,
    NER;

    @Override
    public String toString() {
        return name().toLowerCase();
    }

    public static Optional<NLPStage> parse(final String stage) {
        if (stage== null || stage.isEmpty())
            return Optional.empty();
        try {
            return Optional.of(valueOf(stage.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            //throw new IllegalArgumentException(String.format("\"%s\" is not a valid natural language processing stage.", stage));
            return Optional.empty();
        }
    }
}
