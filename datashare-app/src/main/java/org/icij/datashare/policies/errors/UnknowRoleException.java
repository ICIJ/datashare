package org.icij.datashare.policies.errors;

import org.icij.datashare.policies.Role;

public class UnknowRoleException extends InvalidValueException {
    public UnknowRoleException(String message) {
        super(message);
    }

    public static Role resolveRole(String role) {
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new UnknowRoleException("Invalid role value:" + role);
        }
    }
}
