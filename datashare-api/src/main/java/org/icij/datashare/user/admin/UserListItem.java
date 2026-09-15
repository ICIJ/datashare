package org.icij.datashare.user.admin;

import java.util.List;

public record UserListItem(String uid, String name, String email, List<Permission> permissions) {
    public record Permission(String v1, String v2) {}
}
