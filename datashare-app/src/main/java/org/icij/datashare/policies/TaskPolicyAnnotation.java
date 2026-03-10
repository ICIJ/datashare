package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.payload.Payload;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.tasks.DatashareTaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Function;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;

public class TaskPolicyAnnotation implements ApplyAroundAnnotation<TaskPolicy> {
    private final Authorizer authorizer;
    private final DatashareTaskManager taskManager;
    Logger logger = LoggerFactory.getLogger(TaskPolicyAnnotation.class);

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

            boolean isAllowed;
            Object batchSearchRecord = task.args.get("batchRecord");
            Object batchDownload = task.args.get("batchDownload");
            if (batchSearchRecord instanceof BatchSearchRecord) {
                // BatchSearches are linked to multiple projects
                isAllowed = ((BatchSearchRecord) batchSearchRecord).projects.stream().allMatch(p -> authorizer.can(user.id, domain, p.getId(), annotation.role()));
            } else if (batchDownload instanceof BatchDownload) {
                // BatchDownloads are linked to multiple projects
                isAllowed = ((BatchDownload) batchDownload).projects.stream().allMatch(p -> authorizer.can(user.id, domain, p.getId(), annotation.role()));
            } else {
            // Tasks are linked to ONE project at a time
                String projectId = ofNullable((String) task.args.get(DEFAULT_PROJECT_OPT))
                        .orElseThrow(() -> new IllegalStateException("Task " + taskId + " does not have a project id in its arguments"));
                Authorizer.requireValue(projectId, false);
                // Check if user as role based rights or is owner with access rights (if ownerRole is specified)
                isAllowed = authorizer.can(user.id, domain, projectId, annotation.role());
            }

            boolean hasOwnerRole = annotation.ownerRole() != Role.NONE && isTaskOwner(user, task);
            if (!isAllowed && !hasOwnerRole) {
                return Payload.forbidden();
            }
            return payloadSupplier.apply(context);

        } catch (UnknownTask e) {
            return Payload.notFound();
        } catch (IOException e) {
            logger.error("Failed to get task {}", taskId, e);
            return new Payload(e.getMessage(), 500);
        }
    }
}
