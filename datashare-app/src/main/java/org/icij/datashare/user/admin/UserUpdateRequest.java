package org.icij.datashare.user.admin;

import java.util.List;

public record UserUpdateRequest(
        String email,
        String name,
        String password,
        List<String> groups
) {
    public UserUpdateRequest {
        groups = groups == null ? null : List.copyOf(groups);
    }
}
