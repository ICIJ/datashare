package org.icij.datashare.text.nlp;

import static java.util.Comparator.comparingInt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Comparator;
import org.icij.datashare.text.NamedEntity;

public record NlpTag(int begin, int end, NamedEntity.Category category) {

    public static final Comparator<NlpTag> comparator = comparingInt(NlpTag::begin);

    @JsonCreator
    public NlpTag(
        @JsonProperty("begin") int begin,
        @JsonProperty("start") int end,
        @JsonProperty("category") NamedEntity.Category category
    ) {
        this.begin = begin;
        this.end = end;
        this.category = category;
    }

}
