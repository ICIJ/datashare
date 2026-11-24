package org.icij.datashare.tasks.temporal;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import java.util.List;
import java.util.Map;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.pipeline.StageActivity;
import org.icij.datashare.asynctasks.pipeline.StageEmitReceiver;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.DefaultStageContext;
import org.icij.datashare.asynctasks.ScopedStageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Project;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;

public class StageActivityImpl implements StageActivity {
    static final int EMIT_BATCH_SIZE = 1000;
    private final StageRegistry registry;
    private final TemporalInterlocutor temporal;

    public StageActivityImpl(StageRegistry registry, TemporalInterlocutor temporal) {
        this.registry = registry;
        this.temporal = temporal;
    }

    @Override
    public StageResult run(Stage stage, Map<String, Object> options, List<?> batch, boolean hasNextStage) {
        return process(registry.get(stage), options, batch, hasNextStage);
    }

    private <I, O> StageResult process(StageProcessor<I, O> processor, Map<String, Object> options, List<?> batch,
                                       boolean hasNextStage) {
        ActivityExecutionContext activity = Activity.getExecutionContext();
        String workflowId = activity.getInfo().getWorkflowId();
        StageEmitReceiver receiver = temporal.newWorkflowStub(StageEmitReceiver.class, workflowId);
        List<I> items =
                batch.stream().map(item -> JsonObjectMapper.getMapper().convertValue(item, processor.itemType()))
                     .toList();
        Project project = Project.project(
                new PropertiesProvider(options).get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
        EmitBuffer<O> emitted = new EmitBuffer<>(EMIT_BATCH_SIZE, out -> receiver.onEmitted(processor.stage(),
                                                                                            List.<Object>copyOf(out)));
        // one context for the batch: what the processor builds with it is closed when the activity ends
        try (ActivityHeartbeat ignored = ActivityHeartbeat.start(activity);
             ScopedStageContext<O> ctx = new DefaultStageContext<>(workflowId, project, options, out -> {
                 if (hasNextStage) {
                     emitted.add(out);
                 }
             })) {
            StageResult result = processor.process(items, ctx);
            // only once the batch went through: Temporal retries a failed one, which would emit twice
            emitted.flush();
            return result;
        }
    }
}
