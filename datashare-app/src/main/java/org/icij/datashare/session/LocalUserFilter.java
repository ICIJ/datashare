package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;

import static org.icij.datashare.session.DatashareUser.*;

public class LocalUserFilter extends CookieAuthFilter {
    private final String userName;
    private final Repository repository;

    @Inject
    public LocalUserFilter(final PropertiesProvider propertiesProvider, final Repository repository) {
        super(propertiesProvider.get("protectedUrPrefix").orElse("/"),
                singleUser(propertiesProvider.get("defaultUserName").orElse("local")),
                SessionIdStore.inMemory());
        this.userName = propertiesProvider.get("defaultUserName").orElse("local");
        this.repository = repository;
    }

    //for tests
    public LocalUserFilter(final PropertiesProvider propertiesProvider, final Repository repository, String ...projectNames) {
        super(propertiesProvider.get("protectedUrPrefix").orElse("/"),
                singleUser(propertiesProvider.get("defaultUserName").orElse("local"), projectNames),
                SessionIdStore.inMemory());
        this.userName = propertiesProvider.get("defaultUserName").orElse("local");
        this.repository = repository;
    }

    @Override
    public Payload otherUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        String sessionId = readSessionIdInCookie(context);
        context.setCurrentUser(getUserWithEveryProjects());
        if (sessionId == null) {
            return nextFilter.get()
                    .withCookie(this.authCookie(this.buildCookie(users.find("local"), "/")))
                    .withCookie(CsrfFilter.csrfCookie(CsrfFilter.generateToken()));
        } else {
            return nextFilter.get();
        }
    }

    @Override
    public boolean matches(String uri, Context context) {
        if (uri.startsWith("/api/") || uri.startsWith("/auth/")) {
            return true;
        }
        return super.matches(uri, context);
    }

    @Override protected String cookieName() { return "_ds_session_id"; }
    @Override protected int expiry() { return Integer.MAX_VALUE; }
    @Override protected boolean redirectToLogin(String uri) { return false; }

    protected User getUserWithEveryProjects () {
        // We must cast back to DatashareUser to be able to use the `setProjects` method
        DatashareUser datashareUser = (DatashareUser) users.find(userName);
        datashareUser.setProjects(repository.getProjects());
        return datashareUser;
    }
}
