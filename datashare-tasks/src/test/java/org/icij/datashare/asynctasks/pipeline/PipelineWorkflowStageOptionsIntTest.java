package org.icij.datashare.asynctasks.pipeline;

import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.TimeoutFailure;
import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageActivityOptions;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.asynctasks.temporal.TemporalRule;
import org.icij.datashare.asynctasks.temporal.TemporalWorkerOptions;
import org.icij.datashare.asynctasks.temporal.TemporalWorkers;
import org.icij.datashare.asynctasks.temporal.WorkflowRegistry;
import org.junit.ClassRule;
import org.junit.Test;
import static io.temporal.client.WorkflowOptions.newBuilder;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.WORKFLOWS_DEFAULT;
import static org.junit.Assert.assertThrows;

public class PipelineWorkflowStageOptionsIntTest {
    @ClassRule
    public static TemporalRule temporal = new TemporalRule();

    private final ScriptedStageActivity activity = new ScriptedStageActivity();

    @Test
    public void test_stage_options_apply_to_their_stage() throws Exception {
        activity.sleeps(Stage.INDEX, Duration.ofSeconds(3));

        WorkflowFailedException e = assertThrows(WorkflowFailedException.class, () -> runIndex(
                Map.of(Stage.INDEX, new StageActivityOptions(Duration.ofSeconds(1), 1))));

        assertThat(rootCauseOf(e)).isInstanceOf(TimeoutFailure.class);
        assertThat(activity.calls(Stage.INDEX)).isEqualTo(1);
    }

    @Test
    public void test_attempts_are_bounded() {
        activity.fails(Stage.INDEX, new IllegalStateException("boom"));

        assertThrows(WorkflowFailedException.class, () -> runIndex(
                Map.of(Stage.INDEX, new StageActivityOptions(Duration.ofMinutes(1), 2))));

        assertThat(activity.calls(Stage.INDEX)).isEqualTo(2);
    }

    private Map<Stage, StageResult> runIndex(Map<Stage, StageActivityOptions> activityOptions) throws Exception {
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.registerWorkflow(PipelineWorkflowImpl.class, WORKFLOWS_DEFAULT);
        registry.registerActivity(activity, WORKFLOWS_DEFAULT);
        try (Closeable ignored = new TemporalWorkers(temporal.interlocutor()).start(registry,
                                                                                    List.of(WORKFLOWS_DEFAULT),
                                                                                    new TemporalWorkerOptions(1))) {
            return temporal.interlocutor().newWorkflowStub(PipelineWorkflow.class,
                                                           newBuilder().setTaskQueue(WORKFLOWS_DEFAULT).build())
                           .run(List.of(Stage.INDEX), Map.of(), activityOptions);
        }
    }

    private static Throwable rootCauseOf(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
