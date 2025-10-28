package org.icij.datashare.kestra.plugin;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Output;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.WorkerGroup;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.icij.datashare.asynctasks.TaskFactoryHelper;
import org.icij.datashare.asynctasks.TaskGroupType;
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

    @Getter
    @Builder.Default
    @Schema(title = "User running the task")
    private Property<User> user = Property.ofValue(null);

    abstract protected Class<?> getDatashareTaskClass();

    abstract protected Map<String, Object> datashareArgs(RunContext runContext)
        throws IllegalVariableEvaluationException;

    private org.icij.datashare.asynctasks.Task<DO> asDatashareTask(RunContext runContext)
        throws IllegalVariableEvaluationException {
        User user = runContext.render(this.user).as(User.class).orElse(User.local());
        return new org.icij.datashare.asynctasks.Task<>(this.id, this.getDatashareTaskClass().getName(), user,
            datashareArgs(runContext));
    }

    protected abstract Function<DO, KO> datashareToKestraOutputConverter();

    protected Void progressCallback(String taskId, Double progress, Logger logger) {
        logger.info("task {} - progress {}/{}", taskId, Math.floor(progress * 100), 100);
        return null;
    }

    @Override
    public WorkerGroup getWorkerGroup() {
        return new WorkerGroup(TaskGroupType.Java.name(), WorkerGroup.Fallback.WAIT);
    }

    @Override
    public KO run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        // TODO: update the createScanTask API to provide a logger
        org.icij.datashare.asynctasks.Task<DO> dsTask = asDatashareTask(runContext);
        CommonMode mode = ((DefaultRunContext) runContext).getApplicationContext().getBean(CommonMode.class);
        DatashareTaskFactory taskFactory = mode.get(DatashareTaskFactory.class);
        // TODO: update the createScanTask API to provide a logger
        Callable<?> taskFn = TaskFactoryHelper.createTaskCallable(
            taskFactory, dsTask.name, dsTask,
            dsTask.progress((taskId, p) -> progressCallback(taskId, p, logger)));
        DO dsOutput = (DO) taskFn.call();
        return datashareToKestraOutputConverter().apply(dsOutput);
    }
}