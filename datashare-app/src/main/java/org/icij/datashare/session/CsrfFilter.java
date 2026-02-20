package org.icij.datashare.session;

import net.codestory.http.Context;
import net.codestory.http.NewCookie;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;

import java.security.SecureRandom;

public class CsrfFilter implements Filter {
    static final String CSRF_COOKIE_NAME = "_ds_csrf_token";
    static final String CSRF_HEADER_NAME = "X-DS-CSRF-TOKEN";
    static final String SESSION_COOKIE_NAME = "_ds_session_id";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public boolean matches(String uri, Context context) {
        return uri.startsWith("/api/");
    }

    @Override
    public Payload apply(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        String method = context.method();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return nextFilter.get();
        }
        if (context.cookies().get(SESSION_COOKIE_NAME) == null) {
            return nextFilter.get();
        }
        String cookieValue = null;
        var csrfCookie = context.cookies().get(CSRF_COOKIE_NAME);
        if (csrfCookie != null) {
            cookieValue = csrfCookie.value();
        }
        String headerValue = context.header(CSRF_HEADER_NAME);
        if (cookieValue != null && !cookieValue.isEmpty() && cookieValue.equals(headerValue)) {
            return nextFilter.get();
        }
        return new Payload("application/json", "{\"error\":\"CSRF token wrong or missing\"}", 403);
    }

    public static String generateToken() {
        return Long.toHexString(RANDOM.nextLong()) + Long.toHexString(RANDOM.nextLong());
    }

    public static NewCookie csrfCookie(String token) {
        NewCookie cookie = new NewCookie(CSRF_COOKIE_NAME, token, "/");
        cookie.setHttpOnly(false);
        return cookie;
    }
}
