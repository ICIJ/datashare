package org.icij.datashare.session;

import static org.icij.datashare.cli.DatashareCliOptions.SECURE_SESSION_COOKIE_OPT;

class InvalidSecureSessionCookieException extends RuntimeException {
    final String value;

    InvalidSecureSessionCookieException(String value) {
        super("invalid %s \"%s\": expected true or false".formatted(SECURE_SESSION_COOKIE_OPT, value));
        this.value = value;
    }
}
