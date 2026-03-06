package org.icij.datashare.tasks;

import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Repository;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

public class ImportUserPoliciesTask extends DefaultTask<Integer> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Authorizer authorizer;
    private final Repository repository;
    private final User caller;

    @Inject
    public ImportUserPoliciesTask(Authorizer authorizer, Repository repository, @Assisted User caller) {
        this.authorizer = authorizer;
        this.repository = repository;
        this.caller = caller;
    }

    @Override
    public User getUser() {
        return caller;
    }

    @Override
    public Integer call() {
        if (!authorizer.can(caller.id, Domain.of("*"), "*", Role.INSTANCE_ADMIN)) {
            logger.error("User '{}' is not authorized to run ImportUserPoliciesTask (requires INSTANCE_ADMIN).", caller.id);
            return -1;
        }
        int count = 0;
        for (User user : repository.listUsers()) {
            for (String projectName : user.getApplicationProjectNames()) {
                authorizer.addRoleForUserInProject(user, Role.PROJECT_MEMBER, Domain.DEFAULT, new Project(projectName));
                logger.debug("Granted PROJECT_MEMBER to user '{}' for project '{}'.", user.id, projectName);
                count++;
            }
        }
        logger.info("Imported {} PROJECT_MEMBER policies from OAuth2 user database.", count);
        return count;
    }
}
