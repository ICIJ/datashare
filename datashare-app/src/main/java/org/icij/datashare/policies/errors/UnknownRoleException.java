package org.icij.datashare.policies.errors;

import org.icij.datashare.policies.Role;

public class UnknownRoleException extends InvalidValueException {
    public UnknownRoleException(String message) {
        super(message);
    }

    public static Role resolveRole(String role) {
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new UnknownRoleException("Invalid role value:" + role);
        }
    }
}
