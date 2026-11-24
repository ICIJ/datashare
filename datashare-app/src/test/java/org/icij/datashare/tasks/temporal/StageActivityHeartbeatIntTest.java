package org.icij.datashare.tasks.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.io.Closeable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.fest.assertions.Assertions.assertThat;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageActivityOptions;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.WORKFLOWS_DEFAULT;
import org.icij.datashare.asynctasks.pipeline.PipelineWorkflow;
import org.icij.datashare.asynctasks.pipeline.PipelineWorkflowImpl;
import org.icij.datashare.asynctasks.temporal.TemporalRule;
import org.icij.datashare.asynctasks.temporal.TemporalWorkerOptions;
import org.icij.datashare.asynctasks.temporal.TemporalWorkers;
import org.icij.datashare.asynctasks.temporal.WorkflowRegistry;
import org.junit.ClassRule;
import org.junit.Test;

public class StageActivityHeartbeatIntTest {
    @ClassRule
    public static TemporalRule temporal = new TemporalRule();

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch stopped = new CountDownLatch(1);

    @Test
    public void test_cancelling_the_workflow_stops_the_running_stage() throws Exception {
        StageRegistry stages = new StageRegistry().register(new Endless());
        WorkflowRegistry registry = new WorkflowRegistry();
        registry.registerWorkflow(PipelineWorkflowImpl.class, WORKFLOWS_DEFAULT);
        registry.registerActivity(new StageActivityImpl(stages, temporal.interlocutor()), WORKFLOWS_DEFAULT);

        try (Closeable ignored = new TemporalWorkers(temporal.interlocutor()).start(registry, List.of(WORKFLOWS_DEFAULT),
                                                                                    new TemporalWorkerOptions(1))) {
            PipelineWorkflow workflow = temporal.interlocutor().newWorkflowStub(
                    PipelineWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue(WORKFLOWS_DEFAULT).setWorkflowId("heartbeat-cancel" +
                                                                                               UUID.randomUUID())
                                   .build());
            WorkflowClient.start(workflow::run, List.of(Stage.INDEX), Map.of(),
                                 stages.activityOptions(List.of(Stage.INDEX)));
            assertThat(started.await(20, TimeUnit.SECONDS)).isTrue();

            WorkflowStub.fromTyped(workflow).cancel();

            assertThat(stopped.await(20, TimeUnit.SECONDS)).as("the stage was told to stop").isTrue();
        }
    }

    private class Endless implements StageProcessor<Void, Void> {
        public Stage stage() {return Stage.INDEX;}

        public Class<Void> itemType() {return Void.class;}

        public StageResult process(List<Void> batch, StageContext<Void> ctx) {
            started.countDown();
            try {
                while (!ctx.isCancelled()) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                // the interruption is the cancellation
            }
            stopped.countDown();
            return StageResult.EMPTY;
        }

        @Override
        public StageActivityOptions activityOptions() {
            return new StageActivityOptions(Duration.ofMinutes(5), 1, Duration.ofSeconds(1));
        }
    }
}
