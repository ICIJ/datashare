package org.icij.datashare.project.admin;

/**
 * Outcome of {@link ProjectAdminService#addAdminToProject(String, String)}.
 *
 * <p>Distinguishes "grant applied" from "user did not exist in inventory" so
 * callers can react differently (e.g. the CLI suppresses the warning when the
 * miss happened on the {@code --defaultUserName} fallback path).
 */
public enum GrantResult {
    GRANTED,
    USER_NOT_FOUND
}
