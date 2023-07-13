package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.User;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;

import static org.icij.datashare.session.DatashareUser.*;

public class LocalUserFilter extends CookieAuthFilter {
    private final String userName;
    private final JooqRepository jooqRepository;

    @Inject
    public LocalUserFilter(final PropertiesProvider propertiesProvider, final JooqRepository jooqRepository) {
        super(propertiesProvider.get("protectedUrPrefix").orElse("/"),
                singleUser(propertiesProvider.get("defaultUserName").orElse("local")),
                SessionIdStore.inMemory());
        this.userName = propertiesProvider.get("defaultUserName").orElse("local");
        this.jooqRepository = jooqRepository;
    }

    @Override
    public Payload otherUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        String sessionId = readSessionIdInCookie(context);
        context.setCurrentUser(getUserWithEveryProjects());
        if (sessionId == null) {
            return nextFilter.get().withCookie(this.authCookie(this.buildCookie(users.find("local"), "/")));
        } else {
            return nextFilter.get();
        }
    }

    @Override protected String cookieName() { return "_ds_session_id"; }
    @Override protected int expiry() { return Integer.MAX_VALUE; }
    @Override protected boolean redirectToLogin(String uri) { return false; }

    protected User getUserWithEveryProjects () {
        // We must cast back to DatashareUser to be able to use the `setProjects` method
        DatashareUser datashareUser = (DatashareUser) users.find(userName);
        datashareUser.setProjects(jooqRepository.getProjects());
        return datashareUser;
    }
}
