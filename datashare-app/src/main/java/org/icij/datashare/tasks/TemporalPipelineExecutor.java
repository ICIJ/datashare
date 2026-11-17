package org.icij.datashare.tasks;

import com.google.inject.Inject;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.PipelineExecutor;
import org.icij.datashare.asynctasks.StageActivityOptions;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.pipeline.PipelineWorkflow;
import org.icij.datashare.asynctasks.temporal.TemporalInterlocutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.asynctasks.TaskManagerTemporal.resolveWfTaskQueue;
import static org.icij.datashare.user.User.nullUser;

public class TemporalPipelineExecutor implements PipelineExecutor {
    private static final Logger logger = LoggerFactory.getLogger(TemporalPipelineExecutor.class);
    private static final String PIPELINE_WORKFLOW_KEY = "PipelineWorkflow";
    private final TemporalInterlocutor temporal;
    private final String taskQueue;
    private final StageRegistry registry;
    private final TaskManager taskManager;

    @Inject
    public TemporalPipelineExecutor(TemporalInterlocutor temporal, PropertiesProvider propertiesProvider,
                                    StageRegistry registry, TaskManager taskManager) {
        this.temporal = temporal;
        this.registry = registry;
        this.taskManager = taskManager;
        this.taskQueue = taskQueue(propertiesProvider);
    }

    public static String taskQueue(PropertiesProvider propertiesProvider) {
        return resolveWfTaskQueue(Utils.getRoutingStrategy(propertiesProvider), PIPELINE_WORKFLOW_KEY,
                                  new Group(TaskGroupType.Java));
    }

    @Override
    public boolean run(List<Stage> stages, Map<String, Object> args) throws Exception {
        if (stages.size() == 1 && stages.getFirst().runsOnce()) {
            return runTask(stages.getFirst(), args);
        }
        Map<Stage, StageActivityOptions> activityOptions = registry.activityOptions(stages);
        PipelineWorkflow workflow = temporal.newWorkflowStub(PipelineWorkflow.class,
                                                             WorkflowOptions.newBuilder().setTaskQueue(taskQueue)
                                                                            .setWorkflowId(
                                                                                    "pipeline-" + UUID.randomUUID())
                                                                            .build());
        try {
            Map<Stage, StageResult> results = workflow.run(stages, args, activityOptions);
            results.forEach(
                    (stage, result) -> logger.info("stage {} done: {} updated, {} unchanged, {} skipped, {} failed",
                                                   stage, result.updated(), result.unchanged(), result.skipped(),
                                                   result.failed()));
            return true;
        } catch (WorkflowException e) {
            logger.error("pipeline {} ended without completing: its output is partial", stages, e);
            return false;
        }
    }

    private boolean runTask(Stage stage, Map<String, Object> args) throws Exception {
        Class<?> taskClass = QueuePipelineExecutor.TASK_CLASSES.get(stage);
        if (taskClass == null) {
            logger.warn("no task class for stage {}, skipping it", stage);
            return true;
        }
        String taskId = taskManager.startTask(taskClass, nullUser(), new HashMap<>(args));
        taskManager.awaitTermination(Integer.MAX_VALUE, SECONDS, Set.of(taskId));
        Task.State state = taskManager.getTask(taskId).getState();
        if (state != Task.State.DONE) {
            logger.error("stage {} ended in state {}: its output is partial", stage, state);
            return false;
        }
        return true;
    }
}
