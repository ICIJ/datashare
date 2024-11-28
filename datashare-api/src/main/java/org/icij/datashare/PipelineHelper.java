package org.icij.datashare;

import java.util.List;
import java.util.Objects;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public class PipelineHelper {
    public static final String STAGES_OPT = "stages";
    public static final char STAGES_SEPARATOR = ',';

    private final PropertiesProvider propertiesProvider;
    public final List<Stage> stages;

    public PipelineHelper(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
        stages = stream(propertiesProvider.get(STAGES_OPT).orElse("SCAN,INDEX,NLP"). // defaults existing stages for web mode
                split(String.valueOf(STAGES_SEPARATOR))).map(Stage::valueOf).collect(toList());
        stages.sort(Stage.comparator);
    }

    public boolean has(Stage stage) {
        return stages.contains(stage);
    }

    public String getQueueNameFor(Stage stage) {
        return stage.isFirstEnum() ? null: getQueueName(propertiesProvider, stage);
    }

    public String getOutputQueueNameFor(Stage stage) {
        return stage.isLastEnum() ? null: getQueueName(propertiesProvider, getNextStage(stage));
    }

    private Stage getNextStage(Stage stage) {
        if (stage == stages.get(stages.size() - 1)) return stage.getDefaultNextStage();
        if (!stages.contains(stage)) return stage.getDefaultNextStage();
        return stages.get(stages.indexOf(stage) + 1);
    }

    static String getQueueName(PropertiesProvider propertiesProvider, Stage stage) {
        return getInputQueueName(propertiesProvider) + ":" + stage.name().toLowerCase();
    }

    static String getInputQueueName(PropertiesProvider propertiesProvider) {
        return propertiesProvider.get(PropertiesProvider.QUEUE_NAME_OPTION).orElse("extract:queue");
    }

    @Override
    public String toString() {
        return String.format("Pipeline stages: %s", stages.stream().map(Objects::toString).collect(joining(", ")));
    }
}
