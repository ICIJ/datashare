package org.icij.datashare.user;

public interface ApiKeyRepository {
    ApiKey get(String base64Key);
    ApiKey get(User user);
    
    boolean delete(User user);

    boolean save(ApiKey apiKey);
}
