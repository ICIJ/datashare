package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.TaskGroupType;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;

@TaskGroup(TaskGroupType.Java)
public class GenApiKeyTask extends DefaultTask<String> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ApiKeyRepository apiKeyRepository;
    private final User user;

    @Inject
    public GenApiKeyTask(ApiKeyRepository apiKeyRepository, @Assisted User user) {
        this.apiKeyRepository = apiKeyRepository;
        this.user = user;
    }
    @Override
    public String call() throws Exception {
        SecretKey secretKey = DatashareApiKey.generateSecretKey();
        apiKeyRepository.save(new DatashareApiKey(secretKey, user));
        logger.info("generated secret key for user {}", user.id);
        return DatashareApiKey.getBase64Encoded(secretKey);
    }

    @Override
    public User getUser() {
        return user;
    }
}
