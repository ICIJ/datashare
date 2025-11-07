package org.icij.datashare.kestra.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * This test will only test the main task, this allow you to send any input
 * parameters to your task and test the returning behaviour easily.
 */
@KestraTest(packages = {"org.icij.datashare.kestra.plugin"})
class SleepTaskTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void testRun() throws Exception {
        Integer durationS = 10;
        Map<String, Object> variables = Map.of(
            "inputs", Map.of("durationS", durationS),
            "flow", Map.of(
                "namespace", "org.icij.datashare",
                "tenantId", "default-tenant"
            ),
            "taskrun", Map.of("id", "taskrunid")
        );
        RunContext runContext = runContextFactory.of(variables);
        SleepTask task = SleepTask.builder()
            .durationS(Property.ofExpression("{{inputs.durationS}}"))
            .build();

        SleepTask.Output runOutput = task.run(runContext);

        assertThat(runOutput.getDurationS()).isEqualTo(durationS);
    }
}
