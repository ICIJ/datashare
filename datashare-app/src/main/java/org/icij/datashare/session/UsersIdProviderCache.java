package org.icij.datashare.session;

public interface UsersIdProviderCache extends UsersWritable {
    UsersIdProviderCache NO_CACHE = new UsersIdProviderCache() {};

    @Override
    default net.codestory.http.security.User find(String login) {
        return null;
    }

    @Override
    default net.codestory.http.security.User find(String login, String password) {
        return null;
    }

    @Override
    default boolean saveOrUpdate(net.codestory.http.security.User user) {
        return true;
    }
}
