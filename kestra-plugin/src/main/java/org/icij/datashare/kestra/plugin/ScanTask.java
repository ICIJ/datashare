package org.icij.datashare.kestra.plugin;

import static org.icij.datashare.PropertiesProvider.DATA_DIR_OPT;

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
    title = "Scan task",
    description = "Datashare scan"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Scan a directory and detects files to index",
            code = {"""
                id: scan_workflow
                namespace: org.icij

                inputs:
                  - id: dataDir
                    type: STRING

                tasks:
                    - id: scan
                      type: org.icij.datashare.kestra.plugin.ScanTask
                      dataDir: "{{ inputs.dataDir }}"
                """}
        )
    }
)
public class ScanTask extends DatashareTask<Long, ScanTask.Output> {
    // TODO: create a better integration between DS and kestra, we could leverage kestra features to fully describe
    //  tasks... instead of passing a non documented/flexible object
    @Schema(title = "Directory to scan")
    @NotNull
    private Property<String> dataDir;

    // TODO: add other scan task parameters include OS file and so on...

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of scanned files")
        private final long count;
    }

    @Override
    protected Class<?> getDatashareTaskClass() {
        return org.icij.datashare.tasks.ScanTask.class;
    }

    @Override
    protected Map<String, Object> datashareArgs(RunContext runContext) throws IllegalVariableEvaluationException {
        String dataDir = runContext.render(this.dataDir).as(String.class).get();
        return Map.of(DATA_DIR_OPT, dataDir);
    }

    @Override
    protected Function<Long, Output> datashareToKestraOutputConverter() {
        return (dsOutput) -> Output.builder().count(dsOutput).build();
    }
}
