package org.icij.datashare.user;

import java.util.List;
import java.util.stream.Stream;

public interface UserRepository {
    boolean save(User user);
    User getUser(String userId);
    UserPolicy get(User user, String projectId);
    UserPolicy get(String userId, String projectId);

    List<UserPolicy> getPolicies(User user);

    Stream<UserPolicy> getAllPolicies();
    boolean save(UserPolicy permission);
    boolean delete(User user, String projectId);

}