package org.icij.datashare.tasks;

import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.RecordNotFoundException;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.session.UserPolicyVerifier;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.*;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

@TaskGroup(TaskGroupType.Java)
public class GrantAdminPolicyTask extends DefaultTask<Boolean> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final UserPolicyVerifier userPolicyVerifier;
    private final User user;
    private final Project project;

    @Inject
    public GrantAdminPolicyTask(UserPolicyVerifier userPolicyVerifier, @Assisted User user, @Assisted Project project) {
        this.userPolicyVerifier = userPolicyVerifier;
        this.user = user;
        this.project = project;
    }

    @Override
    public Boolean call() throws Exception {
        try {
            return userPolicyVerifier.saveUserPolicy(user.getId(), project.getId(), new Role[]{Role.ADMIN});
        } catch (RecordNotFoundException e) {
            logger.error("Failed to grant admin role: {}", e.getMessage());
            return false;
        }
    }


    @Override
    public User getUser() {
        return user;
    }
}
