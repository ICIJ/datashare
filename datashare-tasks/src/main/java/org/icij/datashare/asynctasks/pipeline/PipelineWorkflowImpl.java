package org.icij.datashare.asynctasks.pipeline;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageActivityOptions;
import org.icij.datashare.asynctasks.StageResult;

public class PipelineWorkflowImpl implements PipelineWorkflow, StageEmitReceiver {
    private final Map<Stage, StageActivity> stageActivities = new EnumMap<>(Stage.class);
    private final List<Started> pending = new ArrayList<>();
    private List<Stage> stages;
    private Map<String, Object> options;
    private Map<Stage, StageActivityOptions> activityOptions;

    private record Started(Stage stage, Promise<StageResult> result) {}

    @Override
    public Map<Stage, StageResult> run(List<Stage> stages, Map<String, Object> options,
                                       Map<Stage, StageActivityOptions> activityOptions) {
        this.stages = stages;
        this.options = options;
        this.activityOptions = activityOptions == null ? Map.of() : activityOptions;
        Map<Stage, StageResult> results = new EnumMap<>(Stage.class);

        // the source stage is handed an empty batch: it emits what it finds, and onEmitted forwards it
        start(stages.get(0), List.of());

        // An emit signal lands in the history before the completion of the activity that sent it, so once the
        // started activities are done, whatever they emitted has been started too: stop when nothing is pending.
        while (!pending.isEmpty()) {
            List<Started> started = List.copyOf(pending);
            Promise.allOf(started.stream().map(Started::result).toList()).get();
            pending.removeAll(started);
            started.forEach(s -> results.merge(s.stage(), s.result().get(), StageResult::plus));
        }
        return results;
    }

    @Override
    public void onEmitted(Stage from, List<Object> items) {
        int next = stages.indexOf(from) + 1;
        if (next > 0 && next < stages.size()) {
            // fired asynchronously so several batches run concurrently
            start(stages.get(next), items);
        }
    }

    private void start(Stage stage, List<?> batch) {
        StageActivity activity = stageActivities.computeIfAbsent(stage, s -> Workflow.newActivityStub(
                StageActivity.class, toActivityOptions(activityOptions.getOrDefault(s, StageActivityOptions.DEFAULT))));
        pending.add(new Started(stage, Async.function(activity::run, stage, options, batch)));
    }

    private static ActivityOptions toActivityOptions(StageActivityOptions options) {
        ActivityOptions.Builder builder = ActivityOptions.newBuilder()
                                                         .setStartToCloseTimeout(options.startToCloseTimeout());
        if (options.maxAttempts() > 0) {
            builder.setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(options.maxAttempts()).build());
        }
        return builder.build();
    }
}
