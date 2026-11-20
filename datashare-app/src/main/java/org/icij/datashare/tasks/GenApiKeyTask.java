package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.TaskGroupType;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.session.ApiKeyExpiration;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.DatashareApiKey;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;

@TemporalSingleActivityWorkflow(name = "generate-api-key", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class GenApiKeyTask extends DefaultTask<String> implements UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyExpiration apiKeyExpiration;
    private final User user;
    private final Date expirationDate;
    private final List<String> projects;
    private final boolean rotate;

    @AssistedInject
    public GenApiKeyTask(ApiKeyRepository apiKeyRepository, ApiKeyExpiration apiKeyExpiration, @Assisted User user,
                         @Assisted @Nullable Date expirationDate) {
        this(apiKeyRepository, apiKeyExpiration, user, expirationDate, List.of(), true);
    }

    @AssistedInject
    public GenApiKeyTask(ApiKeyRepository apiKeyRepository, ApiKeyExpiration apiKeyExpiration, @Assisted User user,
                         @Assisted @Nullable Date expirationDate, @Assisted List<String> projects) {
        this(apiKeyRepository, apiKeyExpiration, user, expirationDate, projects, false);
    }

    private GenApiKeyTask(ApiKeyRepository apiKeyRepository, ApiKeyExpiration apiKeyExpiration, User user,
                          Date expirationDate, List<String> projects, boolean rotate) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyExpiration = apiKeyExpiration;
        this.user = user;
        this.expirationDate = expirationDate;
        this.projects = projects;
        this.rotate = rotate;
    }

    @Override
    public String call() throws Exception {
        Date expiration = apiKeyExpiration.expirationForNewKey(expirationDate);
        SecretKey secretKey = DatashareApiKey.generateSecretKey();
        DatashareApiKey apiKey = new DatashareApiKey(secretKey, user, expiration, projects);
        if (rotate) {
            apiKeyRepository.replace(apiKey);
        } else {
            apiKeyRepository.save(apiKey);
        }
        logger.info("generated secret key for user {} (store it somewhere safe, datashare cannot retrieve it later)",
                    user.id);
        return DatashareApiKey.getBase64Encoded(secretKey);
    }

    @Override
    public User getUser() {
        return user;
    }
}
