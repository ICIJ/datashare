package org.icij.datashare.session;

import com.google.inject.Inject;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.ApiKeyRepository;

public class ApiKeyStoreAdapter implements ApiKeyStore {
    private final ApiKeyRepository repository;
    private final ApiKeyExpiration apiKeyExpiration;

    @Inject
    public ApiKeyStoreAdapter(ApiKeyRepository repository, ApiKeyExpiration apiKeyExpiration) {
        this.repository = repository;
        this.apiKeyExpiration = apiKeyExpiration;
    }

    @Override
    public ApiKey get(String base64Key) {
        ApiKey apiKey = repository.get(base64Key);
        if (apiKey == null || apiKeyExpiration.isExpired(apiKey)) {
            return null;
        }
        return apiKey;
    }
}
