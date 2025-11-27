package org.icij.datashare.user;

import java.util.List;

public interface UserRepository {
    boolean save(User user);
    User getUser(String userId);
    UserPolicy get(User user, String projectId);
    UserPolicy get(String userId, String projectId);
    List<UserPolicy> list(User user);
    List<UserPolicy> getAll();
    boolean save(UserPolicy permission);
    boolean delete(User user, String projectId);
    User getUserWithPolicies(String userId);
}