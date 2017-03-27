package org.icij.datashare.text.nlp;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import org.icij.datashare.function.ThrowingFunction;


/**
 * Natural Language Processing stages:
 *
 *   - SENTENCE sentence splitting
 *   - TOKEN    tokenization
 *   - LEMMA    lemmatization
 *   - POS      part-of-speech tagging
 *   - NER      named entity recognition
 *
 * Created by julien on 4/4/16.
 */
public enum NlpStage {
    SENTENCE,
    TOKEN,
    LEMMA,
    POS,
    NER;

    public static final Comparator<NlpStage> comparator = Comparator.comparing(NlpStage::ordinal);

    public static Optional<NlpStage> parse(final String stage) {
        if (stage== null || stage.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(stage.toUpperCase(Locale.ROOT)));

        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static ThrowingFunction<List<String>, List<NlpStage>> parseAll =
            list ->
                    list.stream()
                    .map(NlpStage::parse)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList());

}
