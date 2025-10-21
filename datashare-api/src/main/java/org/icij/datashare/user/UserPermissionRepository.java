package org.icij.datashare.user;

import java.util.List;

public interface UserPermissionRepository {
    UserPermission get(User user, String projectId);
    List<UserPermission> list(User user);
    boolean save(UserPermission permission);
    boolean delete(User user, String projectId);
}
