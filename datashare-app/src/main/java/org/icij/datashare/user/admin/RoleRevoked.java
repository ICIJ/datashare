package org.icij.datashare.user.admin;

import org.icij.datashare.policies.Role;

/**
 * Outcome of revoking an instance/domain role from a user via
 * {@code DELETE /api/users/:userId/role}. {@code previousRole} is the role the
 * user held at that scope before the call (null if none); {@code noop} is true
 * when the user did not hold that role to begin with.
 */
public record RoleRevoked(Role role, String userLogin, Role previousRole, boolean noop) {}
