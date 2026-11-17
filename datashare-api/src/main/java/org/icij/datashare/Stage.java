package org.icij.datashare;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toCollection;

public enum Stage {
    SCAN(true, Payload.NONE), SCANIDX(false, Payload.NONE), SCANQUERY(false, Payload.NONE),
    DEDUPLICATE(false, Payload.PATH), INDEX(true, Payload.PATH), ENQUEUEIDX(false, Payload.NONE),
    CATEGORIZE(false, Payload.ID), NLP(true, Payload.ID), CREATENLPBATCHESFROMIDX(false, Payload.NONE, true),
    BATCHNLP(false, Payload.NONE, true), ARTIFACT(false, Payload.ID), LANGUAGE(false, Payload.ID),
    ENTITIES(false, Payload.NONE, true);
    public static final Comparator<Stage> comparator = Comparator.comparing(Stage::ordinal);
    private final boolean isMainStage;
    private final Payload consumed;
    private final boolean runOnce;

    /** What a stage's task takes off the queue named after it. NONE for a stage reading the
     *  filesystem or the index, so enqueuing for it strands everything it is handed. */
    public enum Payload {
        NONE, PATH, ID
    }

    Stage(boolean isMain, Payload consumed) {
        this(isMain, consumed, false);
    }

    /**
     * @param runOnce a stage that is one unit of work run as a task of its own, with its own result: it neither drains
     *                a queue nor emits to a next stage, so it has no stage processor and the executors run it as a task
     */
    Stage(boolean isMain, Payload consumed, boolean runOnce) {
        isMainStage = isMain;
        this.consumed = consumed;
        this.runOnce = runOnce;
    }

    public boolean runsOnce() {
        return runOnce;
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
