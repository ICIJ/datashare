package org.icij.datashare.text.nlp;

import org.icij.datashare.text.NamedEntity;

import java.util.Comparator;

import static java.util.Comparator.comparingInt;
import static org.icij.datashare.text.NamedEntity.Category.NONE;

/**
 * {@code NlpTag} associates a text span {@code [begin, end]} (in characters) to
 *  an {@link NlpStage} and, optionally, for NER tags a Named Entity category
 */
public class NlpTag {

    public static final Comparator<NlpTag> comparator = comparingInt(NlpTag::getBegin);

    private final int begin;
    private final int end;
    private final NlpStage stage;
    private final NamedEntity.Category category;


    NlpTag(NlpStage stage, int begin, int end, NamedEntity.Category category) {
        this.begin = begin;
        this.end = end;
        this.category = category;
        this.stage = stage;
    }

    NlpTag(NlpStage stage, int begin, int end) {
        this(stage, begin, end, NONE);
    }

    public int getBegin() {
        return begin;
    }

    public int getEnd() {
        return end;
    }

    public NamedEntity.Category getCategory() {
        return category;
    }

    public NlpStage getStage() {
        return stage;
    }

}
