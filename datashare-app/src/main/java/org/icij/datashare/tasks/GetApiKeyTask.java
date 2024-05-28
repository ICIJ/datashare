package org.icij.datashare.tasks;

import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;

import javax.inject.Inject;

public class GetApiKeyTask extends DefaultTask<String> implements UserTask  {
    private final ApiKeyRepository apiKeyRepository;
    private final User user;

    @Inject
    public GetApiKeyTask(ApiKeyRepository apiKeyRepository,@Assisted User user) {
        this.apiKeyRepository = apiKeyRepository;
        this.user = user;
    }

    @Override
    public String call() throws Exception {
        ApiKey apiKey = apiKeyRepository.get(user);
        return apiKey != null ? apiKey.getId() : null;
    }

    @Override
    public User getUser() {
        return user;
    }
}
