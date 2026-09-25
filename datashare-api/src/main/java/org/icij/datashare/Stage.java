package org.icij.datashare;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toCollection;

public enum Stage {
    SCAN(true, Payload.NONE), SCANIDX(false, Payload.NONE), DEDUPLICATE(false, Payload.PATH),
    INDEX(true, Payload.PATH), ENQUEUEIDX(false, Payload.NONE), CATEGORIZE(false, Payload.ID),
    NLP(true, Payload.ID), CREATENLPBATCHESFROMIDX(false, Payload.NONE), BATCHNLP(false, Payload.NONE),
    ARTIFACT(false, Payload.ID), LANGUAGE(false, Payload.ID);
    public static final Comparator<Stage> comparator = Comparator.comparing(Stage::ordinal);
    private final boolean isMainStage;
    private final Payload consumed;

    /** What a stage's task takes off the queue named after it. NONE for a stage reading the
     *  filesystem or the index, so enqueuing for it strands everything it is handed. */
    public enum Payload {
        NONE, PATH, ID
    }

    Stage(boolean isMain, Payload consumed) {
        isMainStage = isMain;
        this.consumed = consumed;
    }

    public static Set<Stage> consuming(Payload payload) {
        return stream(values()).filter(stage -> stage.consumed == payload)
                               .collect(toCollection(() -> EnumSet.noneOf(Stage.class)));
    }

    public static Optional<Stage> parse(final String stage) {
        if (stage == null || stage.isEmpty())
            return Optional.empty();
        try {
            return Optional.of(valueOf(stage.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean isFirstEnum() {
        return ordinal() == 0;
    }

    public boolean isLastEnum() {
        return ordinal() == Stage.values().length;
    }

    public Stage getDefaultNextStage() {
        Stage[] stages = Stage.values();
        for (int i = ordinal() + 1; i < stages.length; i++) {
            if (stages[i].isMainStage) {
                return stages[i];
            }
        }
        return NLP;
    }
}
