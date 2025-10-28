package org.icij.datashare.kestra.plugin;

import io.kestra.core.junit.annotations.ExecuteFlow;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.TaskRun;
import io.kestra.core.models.flows.State;
import java.util.List;
import org.icij.datashare.text.nlp.corenlp.models.CoreNlpModels;
import org.junit.jupiter.api.Test;
import io.kestra.core.models.executions.Execution;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.icij.datashare.text.Language.ITALIAN;

/**
 * This test will start Kestra, load the flow located in `src/test/resources/flows/example.yaml`, and execute it.
 * The Kestra configuration file is in `src/test/resources/application.yml`, it configures the in-memory backend for tests.
 */
@KestraTest(startRunner = true) // This annotation starts an embedded Kestra for tests
class ScanIndexNlpTaskRunnerTest {
    @Test
    @ExecuteFlow(value = "flows/example.yaml", timeout = "PT60M")
    void testFlow(Execution execution) {
          List<TaskRun> taskRuns = execution.getTaskRunList();
        assertThat(taskRuns, hasSize(3));
        assertThat((taskRuns.get(2).getState().getCurrent()), is(State.Type.SUCCESS));
        assertThat((Integer) taskRuns.get(2).getOutputs().get("count"), greaterThan(0));
    }
}
