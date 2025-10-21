package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.User;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;

import java.util.HashMap;

import static java.util.Collections.singletonList;

public class YesBasicAuthFilter extends BasicAuthFilter {
    @Inject
    public YesBasicAuthFilter(final PropertiesProvider propertiesProvider) {
        super(propertiesProvider.get("protectedUriPrefix").orElse("/"), "datashare", new DummyUsers());
    }

    static class DummyUsers implements Users {
        @Override public User find(String login, String password) { return find(login);}
        @Override public User find(String login) { return new DatashareUser(new HashMap<>() {{
            put("uid", login);
            put("groups_by_applications", new HashMap<>() {{
                put("datashare", singletonList("local-datashare"));
            }});
        }});}
    }
}
