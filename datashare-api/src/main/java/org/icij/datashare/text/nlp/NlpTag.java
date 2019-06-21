package org.icij.datashare.text.nlp;

import java.util.Comparator;


/**
 * {@code NlpTag} associates a text span {@code [begin, end[} (in characters) to
 *  an {@link NlpStage} and, optionally, a String {@code value}
 *
 * Created by julien on 9/14/16.
 */
public class NlpTag {

    public static final Comparator<NlpTag> comparator =
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

    // NlpTag's stage
    private final NlpStage stage;

    // NlpTag's value
    private final String   value;


    public NlpTag(NlpStage stage, int begin, int end, String value) {
        this.begin = begin;
        this.end   = end;
        this.value = value;
        this.stage = stage;
    }

    public NlpTag(NlpStage stage, int begin, int end) {
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
