package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
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

public class TaskPolicyAnnotation implements ApplyAroundAnnotation<TaskPolicy> {

    private final Authorizer authorizer;
    private final DatashareTaskManager taskManager;

    @Inject
    public TaskPolicyAnnotation(final Authorizer userPolicyVerifier, final DatashareTaskManager taskManager) {
        this.authorizer = userPolicyVerifier;
        this.taskManager = taskManager;

    }

    private static <V extends Serializable> boolean isTaskOwner(Context context, Task<V> task) {
        return task.getUser().equals(context.currentUser());
    }

    @Override
    public Payload apply(TaskPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {
        DatashareUser user = (DatashareUser) context.currentUser();
        if (user == null) {
            return Payload.forbidden();
        }
        String taskId = context.pathParam(annotation.idParam());
        if (taskId == null || taskId.isBlank()) {
            return Payload.forbidden();
        }
        try {
            Task<Serializable> taskView = taskManager.getTask(taskId);

            Project project = Project.project(ofNullable((String) taskView.args.get(DEFAULT_PROJECT_OPT)).orElse(DEFAULT_DEFAULT_PROJECT));

            String projectId = project.name;
            if (projectId == null) {
                return Payload.forbidden();
            }

            if (authorizer.can(user.id, Domain.DEFAULT, projectId, annotation.role())) {
                return payloadSupplier.apply(context);
            } else if (annotation.allowOwner() && isTaskOwner(context, taskView)) {
                return payloadSupplier.apply(context);
            }
            return Payload.forbidden();

        } catch (UnknownTask | IOException e) {
            return Payload.notFound();
        }
    }
}
