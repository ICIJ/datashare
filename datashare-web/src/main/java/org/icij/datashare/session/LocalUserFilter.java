package org.icij.datashare.session;

import com.google.inject.Inject;
import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import org.icij.datashare.PropertiesProvider;

import static org.icij.datashare.session.HashMapUser.local;
import static org.icij.datashare.session.HashMapUser.singleUser;

public class LocalUserFilter extends CookieAuthFilter {
    @Inject
    public LocalUserFilter(final PropertiesProvider propertiesProvider) {
        super(propertiesProvider.get("protectedUrPrefix").orElse("/"), singleUser(local()), SessionIdStore.inMemory());
    }

    @Override
    public Payload otherUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        String sessionId = readSessionIdInCookie(context);
        context.setCurrentUser(users.find("local"));
        if (sessionId == null) {
            return nextFilter.get().withCookie(this.authCookie(this.buildCookie(users.find("local"), "/")));
        } else {
            return nextFilter.get();
        }
    }

    @Override protected String cookieName() { return "_ds_session_id";}
    @Override protected int expiry() { return Integer.MAX_VALUE;}
    @Override protected boolean redirectToLogin(String uri) { return false;}
}
