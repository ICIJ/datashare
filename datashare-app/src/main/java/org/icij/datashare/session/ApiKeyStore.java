package org.icij.datashare.session;

import org.icij.datashare.user.ApiKey;

public interface ApiKeyStore {
    ApiKey get(String apiKey);
}
