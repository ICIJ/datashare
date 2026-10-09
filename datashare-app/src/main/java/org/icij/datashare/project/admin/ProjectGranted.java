package org.icij.datashare.project.admin;

import org.icij.datashare.policies.Role;

/**
 * Outcome of a {@code grant} call. {@code previousRole} is the role the user held before the call:
 * the highest of the project roles that were replaced on a real grant, and the role still held on
 * a {@code noop}.
 * {@code noop} is true only for {@code grantIfNotExists} when the user already held exactly the
 * requested role and no other project roles.
 */
public record ProjectGranted(String name, String userLogin, Role role, Role previousRole, boolean noop) {}
