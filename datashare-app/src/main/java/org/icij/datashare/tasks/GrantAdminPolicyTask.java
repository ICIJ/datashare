package org.icij.datashare.tasks;

import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

@TemporalSingleActivityWorkflow(name = "grant-admin-policy", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class GrantAdminPolicyTask extends DefaultTask<Boolean> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Authorizer authorizer;
    private final User user;
    private final Project project;
    private final Domain domain;

    @Inject
    public GrantAdminPolicyTask(Authorizer authorizer, @Assisted User user, @Assisted Domain domain, @Assisted Project project) {
        this.authorizer = authorizer;
        this.user = user;
        this.domain = domain;
        this.project = project;
    }

    @Override
    public Boolean call() {
        if (authorizer.addProjectAdmin(user.getId(), domain, project.getId())) {
            logger.info("Admin role granted to user {} for project {} in domain {}.", user.getId(), project.getId(), domain.id());
            return true;
        }
        ;
        logger.error("Failed to grant admin role: {}", "Already exists or user/project not found.");
        return false;
    }

    @Override
    public User getUser() {
        return user;
    }
}
