package org.icij.datashare.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.text.indexing.IndexType;

import java.util.Arrays;
import java.util.Objects;

/**
 * Domain model for a access policy on a project, matching the policy table.
 */

@IndexType("UserPolicy")
public record UserPolicy(
        @JsonProperty("userId") String userId,
        @JsonProperty("projectId") String projectId,
        @JsonProperty("roles") Role[] roles
) {
    @JsonCreator
    public UserPolicy {}

    public static UserPolicy create(String userId, String projectId, Role[] roles) {
        return new UserPolicy(userId, projectId, roles);
    }

    public static UserPolicy create(String userId, String projectId) {
        return new UserPolicy(userId, projectId, new Role[] {});
    }

    public static UserPolicy create(User user, String projectId) {
        return UserPolicy.create(user.id, projectId);
    }

    public boolean hasRole(Role role) {
        return Arrays.asList(roles).contains(role);
    }

    public boolean admin() {
        return hasRole(Role.ADMIN);
    }

    public boolean reader() {
        return hasRole(Role.READER);
    }

    public boolean writer() {
        return hasRole(Role.WRITER);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        UserPolicy that = (UserPolicy) o;
        return Objects.deepEquals(roles, that.roles) && Objects.equals(userId, that.userId) && Objects.equals(projectId, that.projectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, projectId, Arrays.hashCode(roles));
    }
}
