package org.icij.datashare.session;

import net.codestory.http.Cookie;
import net.codestory.http.NewCookie;
import org.icij.datashare.PropertiesProvider;
import java.security.SecureRandom;
import java.util.HexFormat;
import static org.icij.datashare.cli.DatashareCliOptions.SECURE_SESSION_COOKIE_OPT;

/**
 * Session id and cookie flags shared by every filter that issues the session cookie. fluent-http draws ids from a
 * shared java.util.Random, whose state one observed id is enough to recover, and leaves the cookie script-readable.
 */
final class SessionCookies {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SESSION_ID_BYTES = 16;

    private SessionCookies() {}

    static String newSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static boolean isSecure(PropertiesProvider propertiesProvider) {
        return propertiesProvider.get(SECURE_SESSION_COOKIE_OPT).map(Boolean::parseBoolean).orElse(false);
    }

    static Cookie harden(Cookie cookie, boolean isSecure) {
        NewCookie newCookie = (NewCookie) cookie;
        return newCookie.setHttpOnly(true).setSecure(isSecure);
    }
}
