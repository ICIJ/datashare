package org.icij.datashare.web.errors;

import net.codestory.http.Context;
import net.codestory.http.errors.HttpException;
import org.icij.datashare.session.DatashareUser;

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

    public static boolean forbiddenIfNotGranted(boolean allowed) {
        if (!allowed) {
            throw new ForbiddenException("Forbidden");
        }
        return true;
    }

    // Single membership gate shared by every project-scoped endpoint: a user may
    // only reach a project's data if they are a member of the project named in the
    // request. Centralised here so the check cannot silently diverge between resources.
    public static void requireGranted(Context context, String project) {
        DatashareUser currentUser = (DatashareUser) context.currentUser();
        forbiddenIfNotGranted(currentUser.isGranted(project));
    }
}
