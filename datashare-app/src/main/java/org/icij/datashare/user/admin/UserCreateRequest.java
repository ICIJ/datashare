package org.icij.datashare.user.admin;

import java.util.List;

public record UserCreateRequest(
        String login,
        String email,
        String name,
        String password,
        String provider,
        List<String> groups
) {
    public UserCreateRequest {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    @Override
    public String toString() {
        return "UserCreateRequest[login=" + login
                + ", email=" + email
                + ", name=" + name
                + ", password=" + (password == null ? "null" : "***")
                + ", provider=" + provider
                + ", groups=" + groups + "]";
    }
}
