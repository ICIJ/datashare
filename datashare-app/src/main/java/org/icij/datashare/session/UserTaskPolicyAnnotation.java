package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.errors.ForbiddenException;
import net.codestory.http.errors.NotFoundException;
import net.codestory.http.errors.UnauthorizedException;
import net.codestory.http.payload.Payload;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.tasks.DatashareTaskManager;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicy;
import org.icij.datashare.web.TaskResource;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;

public class UserTaskPolicyAnnotation implements ApplyAroundAnnotation<TaskPolicy> {

    private final UserPolicyVerifier userPolicyVerifier;
    private final DatashareTaskManager taskManager;

    @Inject
    public UserTaskPolicyAnnotation(final UserPolicyVerifier userPolicyVerifier, final DatashareTaskManager taskManager) throws URISyntaxException {
        this.userPolicyVerifier = userPolicyVerifier;
        this.taskManager = taskManager;

    }

    private static <V extends Serializable> Task<V> forbiddenIfNotSameUser(Context context, Task<V> task) {
        if (!task.getUser().equals(context.currentUser())) throw new ForbiddenException();
        return task;
    }

    private static <T> T notFoundIfUnknown(TaskResource.UnknownTaskThrowingSupplier<T> supplier) throws IOException {
        try {
            return supplier.get();
        } catch (UnknownTask ex) {
            throw new NotFoundException();
        }
    }

    @Override
    public Payload apply(TaskPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {
        DatashareUser user = (DatashareUser) context.currentUser();
        if (user == null) {
            throw new UnauthorizedException();
        }

        String taskId = context.pathParam(annotation.idParam());
        try {
            Task<Serializable> taskView = notFoundIfUnknown(() -> taskManager.getTask(taskId));

            Project project = Project.project(ofNullable((String) taskView.args.get(DEFAULT_PROJECT_OPT)).orElse(DEFAULT_DEFAULT_PROJECT));

            String projectId = project.name;
            if (projectId == null) {
                return Payload.forbidden();
            }
            Optional<UserPolicy> policy = Optional.ofNullable(this.userPolicyVerifier.getUserPolicy(user.id, projectId));
            if (policy.isEmpty()) {
                return Payload.forbidden();
            }

            boolean hasAdminRole = Arrays.stream(annotation.roles())
                    .anyMatch(role -> role == Role.ADMIN);
            // except if they are admin and ADMIN role is in the annotation.
            // non admin users are forbidden from accessing tasks that they don't own
            if (hasAdminRole && !policy.get().isAdmin()) {
                forbiddenIfNotSameUser(context, taskView);
            }


            return enforcePolicyRoles(annotation, policy.get()) ? payloadSupplier.apply(context) : Payload.forbidden();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private boolean enforcePolicyRoles(TaskPolicy annotation, UserPolicy userPolicy) {
        return Arrays.stream(annotation.roles()).allMatch(role -> userPolicyVerifier.enforce(userPolicy.userId(), userPolicy.projectId(), role.name()));
    }
}
