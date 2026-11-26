package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.Cookie;
import net.codestory.http.NewCookie;
import net.codestory.http.SameSite;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.filters.auth.CookieAuthFilter;
import net.codestory.http.payload.Payload;
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
    public Payload apply(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        Payload payload = super.apply(uri, context, nextFilter);
        if (SessionCookies.isHttps(context)) {
            markSessionCookieSecure(payload);
        }
        return payload;
    }

    /** Secure rule shared by the session cookie and any other cookie a filter sets next to it. */
    protected boolean isCookieSecure(Context context) {
        return isSessionCookieSecure || SessionCookies.isHttps(context);
    }

    // authCookie gets no request, so the per-request HTTPS upgrade happens on the cookie already in the payload
    private void markSessionCookieSecure(Payload payload) {
        for (Cookie cookie : payload.cookies()) {
            if (COOKIE_NAME.equals(cookie.name())) {
                ((NewCookie) cookie).setSecure(true);
            }
        }
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
        // Lax rather than Strict so a link to Datashare from another site still arrives signed in
        return cookie.setSecure(isSessionCookieSecure).setSameSite(SameSite.LAX);
    }

    @Override
    protected String cookieName() {
        return COOKIE_NAME;
    }
}
