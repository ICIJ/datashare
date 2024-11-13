package org.icij.datashare;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

public enum Stage {
    SCAN(true),
    SCANIDX(false),
    DEDUPLICATE(false),
    INDEX(true),
    ENQUEUEIDX(false),
    NLP(true),
    CREATENLPBATCHESFROMIDX(false),
    BATCHNLP(false),
    ARTIFACT(false);

    public static final Comparator<Stage> comparator = Comparator.comparing(Stage::ordinal);
    private final boolean isMainStage;

    Stage(boolean isMain) {
        isMainStage = isMain;
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