package org.icij.datashare.tasks;

import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;
import java.util.stream.Collectors;

@TemporalSingleActivityWorkflow(name = "grant-admin-policy", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class GrantAdminPolicyTask extends DefaultTask<Boolean> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Authorizer authorizer;
    private final User user;

    @Inject
    public GrantAdminPolicyTask(Authorizer authorizer, @Assisted User user) {
        this.authorizer = authorizer;
        this.user = user;
    }

    @Override
    public Boolean call() {
        List<CasbinRule> existingAdmins = authorizer.getGroupPermissions().stream()
                .filter(r -> Role.INSTANCE_ADMIN.name().equals(r.getV1()) && "*::*".equals(r.getV2()))
                .collect(Collectors.toList());

        if (existingAdmins.stream().anyMatch(r -> user.getId().equals(r.getV0()))) {
            logger.info("User '{}' already has instance admin role.", user.getId());
            return true;
        }

        if (!existingAdmins.isEmpty()) {
            logger.error("Cannot grant instance admin to '{}': an instance admin already exists.", user.getId());
            return false;
        }

        if (authorizer.addRoleForUserInInstance(user, Role.INSTANCE_ADMIN)
                || authorizer.can(user.getId(), Domain.of("*"), "*", Role.INSTANCE_ADMIN)) {
            logger.info("Instance admin role granted to user '{}'.", user.getId());
            return true;
        }
        logger.error("Failed to grant instance admin role to user '{}'.", user.getId());
        return false;
    }

    @Override
    public User getUser() {
        return user;
    }
}
