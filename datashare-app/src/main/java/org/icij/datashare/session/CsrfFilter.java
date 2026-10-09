package org.icij.datashare.session;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.Mode;
import net.codestory.http.Context;
import net.codestory.http.NewCookie;
import net.codestory.http.filters.Filter;
import net.codestory.http.filters.PayloadSupplier;
import net.codestory.http.payload.Payload;
import java.security.SecureRandom;
import java.util.regex.Pattern;

public class CsrfFilter implements Filter {
    static final String CSRF_COOKIE_NAME = "_ds_csrf_token";
    static final String CSRF_HEADER_NAME = "X-DS-CSRF-TOKEN";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern LOOPBACK_HOST =
            Pattern.compile("(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?", Pattern.CASE_INSENSITIVE);
    private final boolean localMode;

    @Inject
    public CsrfFilter(PropertiesProvider propertiesProvider) {
        this.localMode = propertiesProvider.get("mode").map(CsrfFilter::isLocal).orElse(false);
    }

    @Override
    public boolean matches(String uri, Context context) {
        return uri.startsWith("/api/");
    }

    @Override
    public Payload apply(String uri, Context context, PayloadSupplier nextFilter) throws Exception {
        String method = context.method();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            Payload payload = nextFilter.get();
            if (payload.isSuccess() && needsCsrfCookie(context)) {
                payload = payload.withCookie(csrfCookie(generateToken()));
            }
            return payload;
        }
        if (context.currentUser() == null || ApiKeyFilter.isAuthenticated(context) || isLocalMcpCall(uri, context)) {
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

    // JSON content type forces a CORS preflight; the Host check blocks DNS rebinding
    private boolean isLocalMcpCall(String uri, Context context) {
        String contentType = context.header("Content-Type");
        String host = context.header("Host");
        return localMode && "/api/mcp".equals(uri.split("\\?", 2)[0]) && contentType != null &&
               contentType.toLowerCase().startsWith("application/json") && host != null &&
               LOOPBACK_HOST.matcher(host).matches();
    }

    private static boolean isLocal(String mode) {
        try {
            return Mode.valueOf(mode).isLocal();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean needsCsrfCookie(Context context) {
        return context.currentUser() != null && context.cookies().get(CSRF_COOKIE_NAME) == null;
    }

    private static String generateToken() {
        return Long.toHexString(RANDOM.nextLong()) + Long.toHexString(RANDOM.nextLong());
    }

    private static NewCookie csrfCookie(String token) {
        NewCookie cookie = new NewCookie(CSRF_COOKIE_NAME, token, "/");
        cookie.setHttpOnly(false);
        return cookie;
    }
}
