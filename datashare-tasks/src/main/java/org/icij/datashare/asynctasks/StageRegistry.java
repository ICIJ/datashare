package org.icij.datashare.asynctasks;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.icij.datashare.Stage;

/**
 * The processor of each stage, whichever executor runs it. Processors are long-lived and thread-safe: they hold
 * the services of the process, and read what depends on the run from the {@link StageContext}.
 */
public class StageRegistry {
    private final Map<Stage, StageProcessor<?, ?>> processors = new EnumMap<>(Stage.class);

    public StageRegistry register(StageProcessor<?, ?> processor) {
        if (processors.putIfAbsent(processor.stage(), processor) != null) {
            throw new IllegalArgumentException("a processor is already registered for stage " + processor.stage());
        }
        return this;
    }

    /** The caller knows the stage's item and output types, the registry cannot check them. */
    @SuppressWarnings("unchecked")
    public <I, O> StageProcessor<I, O> get(Stage stage) {
        StageProcessor<?, ?> processor = processors.get(stage);
        if (processor == null) {
            throw new IllegalArgumentException("no processor registered for stage " + stage);
        }
        return (StageProcessor<I, O>) processor;
    }

    /**
     * @throws IllegalArgumentException if one of the stages is not found in the registry
     */
    public Map<Stage, StageActivityOptions> activityOptions(List<Stage> stages) {
        Map<Stage, StageActivityOptions> options = new EnumMap<>(Stage.class);
        stages.forEach(stage -> options.put(stage, Objects.requireNonNull(get(stage).activityOptions(),
                                                                          "activity options of stage " + stage)));
        return options;
    }
}
