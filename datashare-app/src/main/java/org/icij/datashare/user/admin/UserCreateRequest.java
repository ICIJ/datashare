package org.icij.datashare.user.admin;

import java.util.List;
import java.util.Objects;

public record UserCreateRequest(
        String login,
        String email,
        String name,
        String password,
        String provider,
        List<String> groups
) {
    public UserCreateRequest {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(provider, "provider");
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
