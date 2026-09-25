package org.icij.datashare;

import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.model.StatementRepository;
import org.icij.datashare.policies.CasbinRuleAdapter;
import org.icij.datashare.tabular.ExtractionMappingRepository;
import org.icij.datashare.user.ApiKeyRepository;

public interface RepositoryFactory {
    Repository createRepository();

    ApiKeyRepository createApiKeyRepository();

    BatchSearchRepository createBatchSearchRepository();

    CasbinRuleAdapter createCasbinRuleRepository();

    StatementRepository createStatementRepository();

    ExtractionMappingRepository createExtractionMappingRepository();
}
