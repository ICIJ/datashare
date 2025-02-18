package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.TaskGroupType;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TaskGroup(TaskGroupType.Java)
public class DelApiKeyTask extends DefaultTask<Boolean> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ApiKeyRepository apiKeyRepository;
    private final User user;

    @Inject
    public DelApiKeyTask(ApiKeyRepository apiKeyRepository,@Assisted User user) {
        this.apiKeyRepository = apiKeyRepository;
        this.user = user;
    }


    @Override
    public Boolean call() throws Exception {
        Boolean result = apiKeyRepository.delete(user);
        if (result) {
            logger.info("key for user {} has been deleted", user.id);
        } else {
            logger.info("no key for user {}", user.id);
        }
        return result;
    }

    @Override
    public User getUser() {
        return user;
    }
}
