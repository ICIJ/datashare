package org.icij.datashare.policies;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.annotations.ApplyAroundAnnotation;
import net.codestory.http.payload.Payload;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.asynctasks.UnknownTask;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.ProjectProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;

public class TaskPolicyAnnotation implements ApplyAroundAnnotation<TaskPolicy> {
    private final Authorizer authorizer;
    private final TaskManager taskManager;
    Logger logger = LoggerFactory.getLogger(TaskPolicyAnnotation.class);

    @Inject
    public TaskPolicyAnnotation(Authorizer authorizer,
                                TaskManager taskManager) {
        this.authorizer = authorizer;
        this.taskManager = taskManager;
    }

    private static boolean isTaskOwner(DatashareUser user, Task<Serializable> task) {
        return Objects.equals(task.getUser(), user);
    }

    private boolean isAllowedForProjects(List<String> projectIds, TaskPolicy annotation, DatashareUser user, Domain domain, Task<Serializable> task) {
        boolean roleAllowed = projectIds.stream().allMatch(id -> authorizer.can(user.id, domain, id, annotation.role()));
        boolean ownershipEnabled = annotation.ownerRole() != Role.NONE;
        boolean ownerAllowed = ownershipEnabled && isTaskOwner(user, task)
                && projectIds.stream().allMatch(id -> authorizer.can(user.id, domain, id, annotation.ownerRole()));
        return roleAllowed || ownerAllowed;
    }

    private boolean isAllowedSingleTask(Task<Serializable> task, TaskPolicy annotation, DatashareUser user, Domain domain) {
        Object batchSearchRecord = task.args.get("batchRecord");
        Object batchDownload = task.args.get("batchDownload");
        if (batchSearchRecord instanceof BatchSearchRecord bsr) {
            // BatchSearches are linked to multiple projects
            return isAllowedForProjects(bsr.projects.stream().map(ProjectProxy::getId).toList(), annotation, user, domain, task);
        } else if (batchDownload instanceof BatchDownload bd) {
            // BatchDownloads are linked to multiple projects
            return isAllowedForProjects(bd.projects.stream().map(ProjectProxy::getId).toList(), annotation, user, domain, task);
        } else {
            // Tasks are linked to ONE project at a time
            String projectId = ofNullable((String) task.args.get(DEFAULT_PROJECT_OPT))
                    .orElseThrow(() -> new IllegalStateException("Task " + task.id + " does not have a project id in its arguments"));
            Authorizer.requireValue(projectId, false);
            // Check if user as role based rights or is owner with access rights (if ownerRole is specified)
            boolean isAllowed = authorizer.can(user.id, domain, projectId, annotation.role());
            boolean ownershipEnabled = annotation.ownerRole() != Role.NONE;
            boolean canAsOwner = authorizer.can(user.id, domain, projectId, annotation.ownerRole());
            boolean hasOwnerRole = ownershipEnabled && canAsOwner && isTaskOwner(user, task);
            return isAllowed || hasOwnerRole;
        }
    }

    @Override
    public Payload apply(TaskPolicy annotation, Context context, Function<Context, Payload> payloadSupplier) {

        DatashareUser user = Authorizer.requireUser((DatashareUser) context.currentUser());
        //TODO #DOMAIN Currently Domain is not handled so we can't check it from query params
        Domain domain = Authorizer.requireDomain(annotation.domain(), true);
        if (!annotation.singleTask()) {
            // either we should check for every tasks' projects
            // or we enforce wildcard project access (domain level)to do batch operation (current solution).
            boolean isAllowed = authorizer.can(user.id, domain, "*", annotation.role());
            return isAllowed ? payloadSupplier.apply(context) : Payload.forbidden();
        }
        String taskId = Authorizer.requireIdParam(context, annotation.idParam());
        try {
            Task<Serializable> task = taskManager.getTask(taskId);
            boolean isAllowed = isAllowedSingleTask(task, annotation, user, domain);

            return isAllowed ? payloadSupplier.apply(context) : Payload.forbidden();

        } catch (UnknownTask e) {
            return Payload.notFound();
        } catch (IOException e) {
            logger.error("Failed to get task {}", taskId, e);
            return new Payload(e.getMessage(), 500);
        }
    }
}
