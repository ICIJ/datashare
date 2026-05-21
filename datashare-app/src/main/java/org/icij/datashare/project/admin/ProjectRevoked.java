package org.icij.datashare.project.admin;

import org.icij.datashare.policies.Role;

import java.util.List;

/**
 * Outcome of a {@code revoke} call. {@code revokedRoles} lists every role
 * that was actually removed (may be empty). {@code noop} is true only for
 * {@code revokeIfExists} when the user did not exist or held no roles on
 * the project.
 */
public record ProjectRevoked(
        String name,
        String userLogin,
        List<Role> revokedRoles,
        boolean noop) {
}
