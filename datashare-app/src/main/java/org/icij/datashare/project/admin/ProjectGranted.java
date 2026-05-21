package org.icij.datashare.project.admin;

import org.icij.datashare.policies.Role;

/**
 * Outcome of a {@code grant} call. {@code previousRole} is non-null when a
 * different project role was replaced; {@code noop} is true only for
 * {@code grantIfNotExists} when the user already held exactly the requested
 * role and no other project roles.
 */
public record ProjectGranted(
        String name,
        String userLogin,
        Role role,
        Role previousRole,
        boolean noop) {
}
