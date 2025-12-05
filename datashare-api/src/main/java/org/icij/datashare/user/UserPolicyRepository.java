package org.icij.datashare.user;

import java.util.stream.Stream;

public interface UserPolicyRepository {
    UserPolicy get(String userId, String projectId);

    boolean save(UserPolicy userPolicy);

    boolean delete(String userId, String projectId);

    Stream<UserPolicy> getPolicies(String userId);
    Stream<UserPolicy> getAllPolicies();
}