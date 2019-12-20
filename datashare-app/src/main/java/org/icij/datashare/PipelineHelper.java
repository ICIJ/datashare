package org.icij.datashare;

import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.DatashareCliOptions;

import java.util.List;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

public class PipelineHelper {
    private final PropertiesProvider propertiesProvider;
    public final List<DatashareCli.Stage> stages;

    public PipelineHelper(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
        stages = stream(propertiesProvider.getProperties().getProperty(DatashareCliOptions.STAGES_OPT).
                        split(String.valueOf(DatashareCliOptions.ARG_VALS_SEP))).map(DatashareCli.Stage::valueOf).collect(toList());
        stages.sort(DatashareCli.Stage.comparator);
    }

    public boolean has(DatashareCli.Stage stage) {
        return stages.contains(stage);
    }

    public String getQueueNameFor(DatashareCli.Stage stage) {
        if (! has(stage)) throw new IllegalArgumentException("undefined stage " + stage);
        String inputQueueName = getInputQueueName(propertiesProvider);
        return stages.indexOf(stage) == 0 || stages.indexOf(stage) == 1 ? inputQueueName: getQueueName(propertiesProvider, stages.get(stages.indexOf(stage) - 1));
    }

    public static String getQueueName(PropertiesProvider propertiesProvider, DatashareCli.Stage stage) {
        return getInputQueueName(propertiesProvider) + ":" + stage.name().toLowerCase();
    }

    public static String getInputQueueName(PropertiesProvider propertiesProvider) {
        return propertiesProvider.get(PropertiesProvider.QUEUE_NAME_OPTION).orElse("extract:queue");
    }
}
