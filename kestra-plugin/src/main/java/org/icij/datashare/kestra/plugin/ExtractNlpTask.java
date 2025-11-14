package org.icij.datashare.kestra.plugin;

import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MAX_CONTENT_LENGTH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.POLLING_INTERVAL_SECONDS_OPT;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.icij.datashare.text.nlp.Pipeline;

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
            title = "Performs NER",
            code = {"""
                id: ner_workflow
                namespace: org.icij

                inputs:
                  - id: defaultProject
                    type: STRING
                  - id: nlpPipeline
                    required: false
                    type: STRING
                  - id: maxContentLength
                    required: false
                    type: INT
                  - id: maxContentLength
                    required: false
                    type: FLOAT

                tasks:
                  - id: ner
                    type: org.icij.datashare.kestra.plugin.ExtractNlpTask
                    defaultProject: "{{ inputs.defaultProject }}"
                    maxContentLength: "{{ inputs.maxContentLength }}"
                    pollingInterval: "{{ inputs.pollingInterval }}"
                """}
        )
    }
)
public class ExtractNlpTask extends DatashareTask<Long, ExtractNlpTask.Output> {
    // TODO: create a better integration between DS and kestra, we could leverage kestra features to fully describe
    //  tasks... instead of passing a non documented/flexible object
    @Schema(title = "Datashare project of the documents")
    @NotNull
    private Property<String> defaultProject;

    @Schema(title = "Pipeline to use for NER")
    @Builder.Default
    private Property<Pipeline.Type> nlpPipeline = Property.ofValue(CORENLP);

    @Schema(title = "Max content length", description = "Max content length of chunks parsed by the pipeline")
    @Builder.Default
    private Property<Integer> maxContentLength = Property.ofValue(null);

    @Schema(title = "Polling interval in seconds")
    @Builder.Default
    private Property<Integer> pollingInterval = Property.ofValue(null);

    // TODO: add other index task parameters include OS file and so on...

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Number of processed files")
        private final long count;
    }

    @Override
    protected Class<?> getDatashareTaskClass() {
        return org.icij.datashare.tasks.ExtractNlpTask.class;
    }

    @Override
    protected Map<String, Object> datashareArgs(RunContext runContext) throws IllegalVariableEvaluationException {
        String dataDir = runContext.render(this.defaultProject).as(String.class).get();
        HashMap<String, Object> args = new HashMap<>(Map.of(DEFAULT_PROJECT_OPT, dataDir));
        runContext.render(nlpPipeline).as(Pipeline.Type.class)
            .ifPresent(p -> args.put(NLP_PIPELINE_OPT, p.name()));
        runContext.render(maxContentLength).as(Integer.class)
            .ifPresent(l -> args.put(MAX_CONTENT_LENGTH_OPT, l));
        runContext.render(pollingInterval).as(Integer.class)
            .ifPresent(i -> args.put(POLLING_INTERVAL_SECONDS_OPT, i));
        return args;
    }

    @Override
    protected Function<Long, Output> datashareToKestraOutputConverter() {
        return (dsOutput) -> Output.builder().count(dsOutput).build();
    }
}
