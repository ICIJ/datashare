package org.icij.datashare.tasks.temporal;

import io.temporal.activity.Activity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.pipeline.StageActivity;
import org.icij.datashare.asynctasks.pipeline.StageEmitReceiver;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Project;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;

public class StageActivityImpl implements StageActivity {
    /** The workflow id, for what a processor records against the run (artifact manifests). */
    public static final String TASK_ID_OPT = "taskId";
    private final StageRegistry registry;
    private final TemporalInterlocutor temporal;

    public StageActivityImpl(StageRegistry registry, TemporalInterlocutor temporal) {
        this.registry = registry;
        this.temporal = temporal;
    }

    @Override
    public StageResult run(Stage stage, Map<String, Object> options, List<?> batch) {
        return process(registry.get(stage), options, batch);
    }

    private <I, O> StageResult process(StageProcessor<I, O> processor, Map<String, Object> options, List<?> batch) {
        String workflowId = Activity.getExecutionContext().getInfo().getWorkflowId();
        StageEmitReceiver receiver = temporal.newWorkflowStub(StageEmitReceiver.class, workflowId);
        List<I> items =
                batch.stream().map(item -> JsonObjectMapper.getMapper().convertValue(item, processor.itemType()))
                     .toList();
        Map<String, Object> contextOptions = new HashMap<>(options);
        contextOptions.put(TASK_ID_OPT, workflowId);
        Project project = Project.project(
                new PropertiesProvider(options).get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT));
        return processor.process(items, new StageContext<>() {
            public Project project() {return project;}

            public Map<String, Object> options() {return contextOptions;}

            public boolean isCancelled() {return Thread.currentThread().isInterrupted();}

            public void emit(List<O> out) {
                if (!out.isEmpty()) {
                    receiver.onEmitted(processor.stage(), List.copyOf(out));
                }
            }
        });
    }
}
