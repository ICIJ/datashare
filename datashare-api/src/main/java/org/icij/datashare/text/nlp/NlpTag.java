package org.icij.datashare.text.nlp;

import static java.util.Comparator.comparingInt;

import java.util.Comparator;
import org.icij.datashare.text.NamedEntity;

public class NlpTag {

    public static final Comparator<NlpTag> comparator = comparingInt(NlpTag::getBegin);

    private final int begin;
    private final int end;
    private final NamedEntity.Category category;


    NlpTag(int begin, int end, NamedEntity.Category category) {
        this.begin = begin;
        this.end = end;
        this.category = category;
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

}
