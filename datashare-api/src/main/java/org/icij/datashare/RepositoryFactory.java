package org.icij.datashare;

import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.user.ApiKeyRepository;

public interface RepositoryFactory {
    Repository createRepository();

    ApiKeyRepository createApiKeyRepository();

    BatchSearchRepository createBatchSearchRepository();
}
