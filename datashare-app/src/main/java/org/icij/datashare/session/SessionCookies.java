package org.icij.datashare.session;

import net.codestory.http.Context;
import org.icij.datashare.PropertiesProvider;
import java.security.SecureRandom;
import java.util.HexFormat;
import static org.icij.datashare.cli.DatashareCliOptions.SECURE_SESSION_COOKIE_OPT;

/**
 * Session id and cookie flags shared by every filter that issues the session cookie. fluent-http draws ids from a
 * shared java.util.Random, whose state one observed id is enough to recover. The cookie stays script-readable because
 * datashare-client reads it to authenticate preview requests and to detect cookie-based sign-in.
 */
final class SessionCookies {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SESSION_ID_BYTES = 16;
    private static final String FORWARDED_PROTO_HEADER = "x-forwarded-proto";

    private SessionCookies() {}

    static String newSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static boolean isSecure(PropertiesProvider propertiesProvider) {
        return propertiesProvider.get(SECURE_SESSION_COOKIE_OPT).map(SessionCookies::parseStrictBoolean).orElse(false);
    }

    // A TLS-terminating proxy knows the scheme the browser used, so its header wins over the connection to datashare
    static boolean isHttps(Context context) {
        String forwardedProto = context.header(FORWARDED_PROTO_HEADER);
        if (forwardedProto != null && !forwardedProto.isBlank()) {
            String browserProto = forwardedProto.split(",")[0].trim();
            return "https".equalsIgnoreCase(browserProto);
        }
        return context.request().isSecure();
    }

    // Boolean.parseBoolean reads "yes" or a typo as false, which would silently leave cookies sent over plain HTTP
    private static boolean parseStrictBoolean(String value) {
        String normalizedValue = value.trim().toLowerCase();
        return switch (normalizedValue) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new InvalidSecureSessionCookieException(value);
        };
    }
}
