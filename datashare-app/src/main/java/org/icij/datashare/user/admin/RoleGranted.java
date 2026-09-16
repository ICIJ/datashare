package org.icij.datashare.user.admin;

import org.icij.datashare.policies.Role;

/**
 * Outcome of granting an instance/domain role to a user via
 * {@code PUT /api/users/:userId/role}. {@code previousRole} is the role the
 * user already held at that scope (null if none); {@code noop} is true when
 * the user already held exactly the requested role.
 */
public record RoleGranted(Role role, String userLogin, Role previousRole, boolean noop) {}
