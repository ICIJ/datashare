package org.icij.datashare.session;

import com.google.inject.Inject;
import org.icij.datashare.user.ApiKey;
import org.icij.datashare.user.ApiKeyRepository;

public class ApiKeyStoreAdapter implements ApiKeyStore {
    private final ApiKeyRepository repository;

    @Inject
    public ApiKeyStoreAdapter(ApiKeyRepository repository) {
        this.repository = repository;
    }

    @Override
    public String getLogin(String base64Key) {
        ApiKey apiKey = repository.get(base64Key);
        return apiKey == null ? null: apiKey.getUser().id;
    }
}
