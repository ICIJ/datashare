package org.icij.datashare.text.nlp;

import java.util.Comparator;


/**
 * {@code Tag} associates a text span {@code [begin, end[} (in characters) to
 *  an {@link NlpStage} and, optionally, a String {@code value}
 *
 * Created by julien on 9/14/16.
 */
public class Tag {

    public static final Comparator<Tag> comparator =
            (t1, t2) ->
                    Integer.valueOf(
                            t1.getBegin()
                    ).compareTo(
                            t2.getBegin()
                    );

    // Begin offset (chars)
    private final int begin;

    // End offset (chars)
    private final int end;

    // Tag's stage
    private final NlpStage stage;

    // Tag's value
    private final String   value;


    public Tag(NlpStage stage, int begin, int end, String value) {
        this.begin = begin;
        this.end   = end;
        this.value = value;
        this.stage = stage;
    }

    public Tag(NlpStage stage, int begin, int end) {
        this(stage, begin, end, "");
    }


    public int getBegin() {
        return begin;
    }

    public int getEnd() {
        return end;
    }

    public String getValue() {
        return value;
    }

    public NlpStage getStage() {
        return stage;
    }

}
