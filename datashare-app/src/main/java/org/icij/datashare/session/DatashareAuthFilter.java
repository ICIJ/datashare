package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.User;
import net.codestory.http.security.Users;

/**
 * Base class for Datashare cookie-based authentication filters.
 * Provides shared behavior: cookie name, path matching (including
 * static-extension bypass prevention on /api/ and /auth/ paths),
 * session resolution in {@link #otherUri}, and common overrides.
 */
public abstract class DatashareAuthFilter extends CookieAuthFilter {
    static final String COOKIE_NAME = "_ds_session_id";

    protected DatashareAuthFilter(String uriPrefix, Users users, SessionIdStore sessionIdStore) {
        super(uriPrefix, users, sessionIdStore);
    }

    @Override
    public boolean matches(String uri, Context context) {
        // Do not skip authentication based on file extension for API paths.
        // CookieAuthFilter.matches() excludes URIs ending in static file
        // extensions (.css, .js, .png, etc.), which allows attackers to bypass
        // authentication by appending an extension to API routes.
        if (uri.startsWith("/api/") || uri.startsWith("/auth/")) {
            return true;
        }
        return super.matches(uri, context) || uri.isEmpty() || uri.equals("/");
    }

    @Override
    protected Payload otherUri(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        if (context.currentUser() != null) {
            return nextFilter.get();
        }
        String sessionId = readSessionIdInCookie(context);
        if (sessionId != null) {
            String login = sessionIdStore.getLogin(sessionId);
            if (login != null) {
                User user = users.find(login);
                if (user != null) {
                    context.setCurrentUser(user);
                    return nextFilter.get();
                }
            }
        }
        if (uri.equals("/") || uri.isEmpty()) {
            return nextFilter.get();
        }
        return new Payload(401);
    }

    @Override protected String cookieName() { return COOKIE_NAME; }
    @Override protected boolean redirectToLogin(String uri) { return false; }
}
