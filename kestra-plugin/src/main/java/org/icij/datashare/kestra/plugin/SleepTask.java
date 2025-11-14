package org.icij.datashare.kestra.plugin;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder(toBuilder = true)
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Sleep task",
    description = "Does nothing but sleeping"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Index scanned files into ES",
            code = {"""
                id: sleep_tasks
                namespace: org.icij.datashare
                
                inputs:
                  - id: durationS
                    type: INT
                
                tasks:
                  - id: sleep
                    type: org.icij.datashare.kestra.plugin.SleepTask
                    durationS: {{inputs.durationS}}
                """}
        )
    }
)
public class SleepTask extends DatashareTask<Integer, SleepTask.Output> {
    @Schema(title = "Sleep durations in seconds")
    @NotNull
    private Property<ArrayList<Integer>> sleepDurations;

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Duration slept in seconds")
        private final Integer totalSleepDurationS;
    }

    @Override
    protected Class<?> getDatashareTaskClass() {
        return org.icij.datashare.tasks.SleepTask.class;
    }

    @Override
    protected Map<String, Object> datashareArgs(RunContext runContext) throws IllegalVariableEvaluationException {
        ArrayList<Integer> durations = runContext.render(this.sleepDurations).asList(Integer.class);
        return Map.of("sleepDurations", durations);
    }

    @Override
    protected Function<Integer, Output> datashareToKestraOutputConverter() {
        return (total) -> Output.builder().totalSleepDurationS(total).build();
    }
}
