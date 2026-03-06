package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.payload.Payload;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tasks.DatashareTaskManager;

import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Function;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;

public class TaskPolicyAnnotation implements ApplyAroundAnnotation<TaskPolicy> {
    private final Authorizer authorizer;
    private final DatashareTaskManager taskManager;

    @Inject
    public TaskPolicyAnnotation(Authorizer authorizer,
                                DatashareTaskManager taskManager) {
        this.authorizer = authorizer;
        this.taskManager = taskManager;
    }

    private static boolean isTaskOwner(DatashareUser user, Task<Serializable> task) {
        return Objects.equals(task.getUser(), user);
    }
    @Override
    public Payload apply(TaskPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {

        DatashareUser user = Authorizer.requireCurrentUser(context);
        //TODO #DOMAIN Currently Domain is not handled so we can't check it from query params
        Domain domain = Authorizer.requireDomain(annotation.domain(), true);
        String taskId = Authorizer.requireIdParam(context, annotation.idParam());

        try {
            Task<Serializable> task = taskManager.getTask(taskId);

            // Tasks are linked to ONE project at a time
            // BatchSearches are not handled yet (they are linked to multiple projects...)
            String projectId = ofNullable((String) task.args.get(DEFAULT_PROJECT_OPT))
                    .orElseThrow(() -> new IllegalStateException("Task " + taskId + " does not have a project id in its arguments"));
            Authorizer.requireValue(projectId, false);

            // Check if user as role based rights or is owner with access rights (if ownerRole is specified)
            boolean hasRole = authorizer.can(user.id, domain, projectId, annotation.role());
            boolean hasOwnerRole = annotation.ownerRole() != Role.NONE && authorizer.can(user.id, domain, projectId, annotation.ownerRole()) && isTaskOwner(user, task);
            if (!hasRole && !hasOwnerRole) {
                return Payload.forbidden();
            }
            return payloadSupplier.apply(context);

        } catch (UnknownTask | IOException e) {
            return Payload.notFound();
        }
    }
}
