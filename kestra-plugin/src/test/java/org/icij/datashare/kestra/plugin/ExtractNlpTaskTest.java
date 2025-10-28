package org.icij.datashare.kestra.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;

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
@KestraTest(packages = {"org.icij.datashare.kestra.plugin"}, application = DatashareModeFactory.class)
class ExtractNlpTaskTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void testRun() throws Exception {
        String defaultProject = "local-datashare";
        RunContext runContext = runContextFactory.of(Map.of("inputs", Map.of(DEFAULT_PROJECT_OPT, defaultProject)));
        ExtractNlpTask task = ExtractNlpTask.builder()
            .defaultProject(Property.ofExpression("{{inputs.defaultProject}}"))
            .build();

        ExtractNlpTask.Output runOutput = task.run(runContext);

        assertThat(runOutput.getCount()).isEqualTo(0);
    }
}
