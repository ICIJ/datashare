package org.icij.datashare.web.errors;

import net.codestory.http.errors.HttpException;

public class ForbiddenException extends HttpException {
    public ForbiddenException(final String message) {
        super(403, message);
    }

    public static boolean forbiddenIfNotEnoughRole(boolean allowed) {
        if (!allowed) {
            throw new ForbiddenException("Cannot grant a role with higher privileges than your own");
        }
        return true;
    }
}
