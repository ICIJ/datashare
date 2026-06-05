package org.icij.datashare.project.admin;

/**
 * Thrown by {@link ProjectAdminService#grant} and {@link ProjectAdminService#revoke}
 * when the target user does not exist in the inventory. Distinct from
 * {@code org.icij.datashare.user.admin.UserNotFoundException} which lives in
 * a different bounded context; catch sites must import the right one.
 */
public class UserNotFoundException extends Exception {
    public UserNotFoundException(String login) {
        super("user '" + login + "' not found");
    }

    public UserNotFoundException(String login, String hint) {
        super("user '" + login + "' not found; " + hint);
    }
}
