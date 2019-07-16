package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.User;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;

public class YesBasicAuthFilter extends BasicAuthFilter {
    @Inject
    public YesBasicAuthFilter(final PropertiesProvider propertiesProvider) {
        super(propertiesProvider.get("protectedUrPrefix").orElse("/"), "datashare", new DummyUsers());
    }

    private static class DummyUsers implements Users {
        @Override public User find(String login, String password) { return find(login);}
        @Override public User find(String login) { return new HashMapUser(login);}
    }
}
