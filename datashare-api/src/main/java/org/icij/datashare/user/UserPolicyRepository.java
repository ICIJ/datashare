package org.icij.datashare.user;

import java.util.List;

public interface UserPolicyRepository {
    UserPolicy get(User user, String projectId);
    List<UserPolicy> list(User user);
    List<UserPolicy> getAll();
    boolean save(UserPolicy permission);
    boolean delete(User user, String projectId);
}
