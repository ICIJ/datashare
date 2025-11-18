package org.icij.datashare;

import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.user.ApiKeyRepository;
import org.icij.datashare.user.UserPolicyRepository;
import org.icij.datashare.user.UserRepository;

public interface RepositoryFactory {
    Repository createRepository();

    ApiKeyRepository createApiKeyRepository();

    BatchSearchRepository createBatchSearchRepository();

    UserPolicyRepository createPolicyRepository();

    UserRepository createUserRepository();
}
