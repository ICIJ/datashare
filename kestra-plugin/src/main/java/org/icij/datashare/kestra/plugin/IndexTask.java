package org.icij.datashare.kestra.plugin;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    title = "Index task",
    description = "Datashare indexing"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Index scanned files into ES",
            code = {"""
                id: index_workflow
                namespace: org.icij

                inputs:
                  - id: defaultProject
                    type: STRING

                tasks:
                  - id: index
                    type: org.icij.datashare.kestra.plugin.IndexTask
                    defaultProject: "{{ inputs.defaultProject }}"
                """}
        )
    }
)
public class IndexTask extends DatashareTask<Long, IndexTask.Output> {
    // TODO: create a better integration between DS and kestra, we could leverage kestra features to fully describe
    //  tasks... instead of passing a non documented/flexible object
    @Schema(title = "Datashare project where the files will be indexed")
    @NotNull
    private Property<String> defaultProject;

    // TODO: add other index task parameters include OS file and so on...

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of indexed files")
        private final long count;
    }

    @Override
    protected Class<?> getDatashareTaskClass() {
        return org.icij.datashare.tasks.IndexTask.class;
    }

    @Override
    protected Map<String, Object> datashareArgs(RunContext runContext) throws IllegalVariableEvaluationException {
        String dataDir = runContext.render(this.defaultProject).as(String.class).get();
        return Map.of(DEFAULT_PROJECT_OPT, dataDir);
    }

    @Override
    protected Function<Long, Output> datashareToKestraOutputConverter() {
        return (dsOutput) -> Output.builder().count(dsOutput).build();
    }
}
