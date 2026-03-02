package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.payload.Payload;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tasks.DatashareTaskManager;
import org.icij.datashare.text.Project;

import java.io.IOException;
import java.io.Serializable;
import java.util.function.Function;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;

public class TaskPolicyAnnotation extends AbstractPolicyAnnotation<TaskPolicy> {

    private final DatashareTaskManager taskManager;

    @Inject
    public TaskPolicyAnnotation(Authorizer authorizer,
                                DatashareTaskManager taskManager) {
        super(authorizer);
        this.taskManager = taskManager;
    }

    @Override
    public Payload apply(TaskPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {

        DatashareUser user = requireUser(context);

        String taskId = context.pathParam(annotation.idParam());
        if (taskId == null || taskId.isBlank()) {
            return Payload.forbidden();
        }

        try {
            Task<Serializable> task = taskManager.getTask(taskId);

            Project project = Project.project(ofNullable((String) task.args.get(DEFAULT_PROJECT_OPT)).orElse(DEFAULT_DEFAULT_PROJECT));

            String projectId = project.name;
            if (projectId == null) {
                return Payload.forbidden();
            }

            if (isAllowed(user, projectId, annotation.role()) || (annotation.allowOwner() && task.getUser().equals(user))) {
                return payloadSupplier.apply(context);
            }

            return Payload.forbidden();

        } catch (UnknownTask | IOException e) {
            return Payload.notFound();
        }
    }
}
