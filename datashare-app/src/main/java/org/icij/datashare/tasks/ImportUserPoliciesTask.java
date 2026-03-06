package org.icij.datashare.tasks;

import org.icij.datashare.Repository;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

public class ImportUserPoliciesTask extends DefaultTask<Integer> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Authorizer authorizer;
    private final Repository repository;

    @Inject
    public ImportUserPoliciesTask(Authorizer authorizer, Repository repository) {
        this.authorizer = authorizer;
        this.repository = repository;
    }

    @Override
    public Integer call() {
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
