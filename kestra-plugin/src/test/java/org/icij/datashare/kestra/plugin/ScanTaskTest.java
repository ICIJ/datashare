package org.icij.datashare.kestra.plugin;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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
class ScanTaskTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void testRun() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of("inputs", Map.of("path", "some_path")));

        ScanTask task = ScanTask.builder()
            .path(Property.ofExpression("{{inputs.path}}"))
            .build();

        ScanTask.Output runOutput = task.run(runContext);

        assertThat(runOutput.getCount(), is(0));
    }
}
