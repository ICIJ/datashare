package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.PipelineExecutor;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import static java.util.Map.entry;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.user.User.nullUser;

public class QueuePipelineExecutor implements PipelineExecutor {
    private static final Logger logger = LoggerFactory.getLogger(QueuePipelineExecutor.class);
    public static final Map<Stage, Class<?>> TASK_CLASSES =
            Map.ofEntries(entry(Stage.SCAN, ScanTask.class), entry(Stage.SCANIDX, ScanIndexTask.class),
                          entry(Stage.SCANQUERY, ScanQueryTask.class), entry(Stage.DEDUPLICATE, DeduplicateTask.class),
                          entry(Stage.INDEX, IndexTask.class), entry(Stage.ENQUEUEIDX, EnqueueFromIndexTask.class),
                          entry(Stage.CATEGORIZE, CategorizeTask.class),
                          entry(Stage.CREATENLPBATCHESFROMIDX, CreateNlpBatchesFromIndex.class),
                          entry(Stage.NLP, ExtractNlpTask.class), entry(Stage.ARTIFACT, ArtifactTask.class),
                          entry(Stage.LANGUAGE, LanguageDetectTask.class),
                          entry(Stage.ENTITIES, StructuredEntityExtractionTask.class));
    private final TaskManager taskManager;

    @Inject
    public QueuePipelineExecutor(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public boolean run(List<Stage> stages, Map<String, Object> args) throws Exception {
        Map<String, Stage> stagesByTaskId = new LinkedHashMap<>();
        String upstreamTaskId = null;
        for (Stage stage : new LinkedHashSet<>(stages)) {
            Class<?> taskClass = TASK_CLASSES.get(stage);
            if (taskClass == null) {
                logger.warn("no task class for stage {}, skipping it", stage);
                continue;
            }
            Map<String, Object> stageArgs = new HashMap<>(args);
            if (upstreamTaskId != null) {
                stageArgs.put(UpstreamGate.UPSTREAM_TASK_ID, upstreamTaskId);
            }
            upstreamTaskId = taskManager.startTask(taskClass, nullUser(), stageArgs);
            stagesByTaskId.put(upstreamTaskId, stage);
        }
        taskManager.awaitTermination(Integer.MAX_VALUE, SECONDS, stagesByTaskId.keySet());
        boolean completed = true;
        for (Map.Entry<String, Stage> startedStage : stagesByTaskId.entrySet()) {
            Task.State state = taskManager.getTask(startedStage.getKey()).getState();
            if (state != Task.State.DONE) {
                logger.error("stage {} ended in state {}: its output is partial", startedStage.getValue(), state);
                completed = false;
            }
        }
        return completed;
    }
}
