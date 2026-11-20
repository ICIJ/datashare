package org.icij.datashare.user;

import java.util.List;

public interface ApiKeyRepository {
    ApiKey get(String base64Key);

    ApiKey get(User user);

    List<ApiKey> getAll(User user);

    boolean delete(User user);

    boolean delete(User user, String hashedKey);

    boolean save(ApiKey apiKey);

    boolean replace(ApiKey apiKey);
}
