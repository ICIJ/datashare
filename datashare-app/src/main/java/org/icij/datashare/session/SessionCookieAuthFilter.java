package org.icij.datashare.session;

import net.codestory.http.Cookie;
import net.codestory.http.NewCookie;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.Users;

/**
 * Base of every filter that issues the {@value #COOKIE_NAME} cookie. A filter extending fluent-http's
 * {@link CookieAuthFilter} directly would draw its session ids from a shared java.util.Random, which one observed id
 * is enough to predict.
 */
abstract class SessionCookieAuthFilter extends CookieAuthFilter {
    static final String COOKIE_NAME = "_ds_session_id";
    private final boolean isSessionCookieSecure;

    protected SessionCookieAuthFilter(String uriPrefix, Users users, SessionIdStore sessionIdStore,
                                      boolean isSessionCookieSecure) {
        super(uriPrefix, users, sessionIdStore);
        this.isSessionCookieSecure = isSessionCookieSecure;
    }

    @Override
    protected String newSessionId(String login) {
        String sessionId = SessionCookies.newSessionId();
        sessionIdStore.put(sessionId, login);
        return sessionId;
    }

    @Override
    protected Cookie authCookie(String value) {
        NewCookie cookie = (NewCookie) super.authCookie(value);
        return cookie.setSecure(isSessionCookieSecure);
    }

    @Override
    protected String cookieName() {
        return COOKIE_NAME;
    }
}
