package org.icij.datashare.user;

import java.util.List;
import java.util.stream.Stream;

public interface UserRepository {
    boolean save(User user);
    User getUser(String userId);
    UserPolicy get(User user, String projectId);
    UserPolicy get(String userId, String projectId);
    List<UserPolicy> list(User user);

    Stream<UserPolicy> getAll();
    boolean save(UserPolicy permission);
    boolean delete(User user, String projectId);

    User getAllPolicies(String userId);
}