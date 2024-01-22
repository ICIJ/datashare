package org.icij.datashare;

import java.util.List;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

public class PipelineHelper {
    public static final String STAGES_OPT = "stages";
    public static final char STAGES_SEPARATOR = ',';

    private final PropertiesProvider propertiesProvider;
    public final List<Stage> stages;

    public PipelineHelper(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
        stages = stream(propertiesProvider.get(STAGES_OPT).orElse("SCAN,INDEX,NLP"). // defaults for web mode
                split(String.valueOf(STAGES_SEPARATOR))).map(Stage::valueOf).collect(toList());
        stages.sort(Stage.comparator);
    }

    public boolean has(Stage stage) {
        return stages.contains(stage);
    }

    public String getQueueNameFor(Stage stage) {
        if (! has(stage)) throw new IllegalArgumentException("undefined stage " + stage);
        return stage.isFirstEnum() ? null: getQueueName(propertiesProvider, stage);
    }

    public String getOutputQueueNameFor(Stage stage) {
        // get queue for next stage
        if (! has(stage)) throw new IllegalArgumentException("undefined stage " + stage);
        return stage.isLastEnum() ? null: getQueueName(propertiesProvider, getNextStage(stage));
    }

    private Stage getNextStage(Stage stage) {
        return stage == stages.get(stages.size() - 1) ? stage.getDefaultNextStage():stages.get(stages.indexOf(stage) + 1);
    }

    static String getQueueName(PropertiesProvider propertiesProvider, Stage stage) {
        return getInputQueueName(propertiesProvider) + ":" + stage.name().toLowerCase();
    }

    static String getInputQueueName(PropertiesProvider propertiesProvider) {
        return propertiesProvider.get(PropertiesProvider.QUEUE_NAME_OPTION).orElse("extract:queue");
    }
}
