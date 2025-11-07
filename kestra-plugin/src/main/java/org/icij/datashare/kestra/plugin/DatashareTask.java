package org.icij.datashare.kestra.plugin;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Output;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.WorkerGroup;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.icij.datashare.asynctasks.TaskFactoryHelper;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.WeightedProgress;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.user.User;
import org.slf4j.Logger;


// TODO: add lombok stuff here
@SuperBuilder(toBuilder = true)
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class DatashareTask<DO extends Serializable, KO extends Output> extends Task
    implements RunnableTask<KO> {

    private static final String PROGRESS_ID_PREFIX = "progress-";

    @Getter
    @Builder.Default
    @Schema(title = "User running the task")
    private Property<User> user = Property.ofValue(null);

    @Getter
    @Builder.Default
    @Schema(title = "Task progress weight")
    private Property<Integer> progressWeight = Property.ofValue(1);

    abstract protected Class<?> getDatashareTaskClass();

    abstract protected Map<String, Object> datashareArgs(RunContext runContext)
        throws IllegalVariableEvaluationException;

    private org.icij.datashare.asynctasks.Task<DO> asDatashareTask(RunContext runContext)
        throws IllegalVariableEvaluationException {
        String taskRunId = ((Map<?, ?>) runContext.getVariables().get("taskrun")).get("id").toString();
        User user = runContext.render(this.user).as(User.class).orElse(User.local());
        return new org.icij.datashare.asynctasks.Task<>(taskRunId, this.getDatashareTaskClass().getName(), user,
            datashareArgs(runContext));
    }

    protected abstract Function<DO, KO> datashareToKestraOutputConverter();

    private Void progressCallback(String taskRunId, Double progress, Logger logger, KVStore kvStore,
                                  int progressWeight) {
        logger.info("task {} - progress {}/{}", taskRunId, Math.floor(progress * 100), 100);
        WeightedProgress weightedProgress = new WeightedProgress(progress, 1.0f, progressWeight);
        KVValueAndMetadata keyValueAndMetadata = new KVValueAndMetadata(null, weightedProgress);
        try {
            kvStore.put(PROGRESS_ID_PREFIX + taskRunId, keyValueAndMetadata, true);
        } catch (IOException e) {
            logger.error("failed to publish progress for task {}", taskRunId, e);
        }
        return null;
    }

    @Override
    public WorkerGroup getWorkerGroup() {
        return new WorkerGroup(TaskGroupType.Java.name(), WorkerGroup.Fallback.WAIT);
    }

    @Override
    public KO run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        KVStore kvStore = getKvStore(runContext);
        // TODO: update the createScanTask API to provide a logger
        org.icij.datashare.asynctasks.Task<DO> dsTask = asDatashareTask(runContext);
        DatashareTaskFactory taskFactory = DatashareMode.mode().get(DatashareTaskFactory.class);
        Integer progressWeight = runContext.render(this.progressWeight).as(Integer.class).orElse(1);
        // TODO: update the createScanTask API to provide a logger
        Callable<?> taskFn = TaskFactoryHelper.createTaskCallable(
            taskFactory, dsTask.name, dsTask,
            dsTask.progress((taskId, p) -> progressCallback(taskId, p, logger, kvStore, progressWeight)));
        DO dsOutput = (DO) taskFn.call();
        return datashareToKestraOutputConverter().apply(dsOutput);
    }

    private static KVStore getKvStore(RunContext runContext) throws IllegalVariableEvaluationException {
        // We could also read namespace from variables
        String namespace = runContext.render("{{ flow.namespace }}");
        return runContext.namespaceKv(namespace);
    }
}