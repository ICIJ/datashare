package org.icij.datashare.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.text.indexing.IndexType;

/**
 * Domain model for a access policy on a project, matching the policy table.
 */

@IndexType("UserPolicy")
public record UserPolicy(
        @JsonProperty("userId") String userId,
        @JsonProperty("projectId") String projectId,
        @JsonProperty("read") boolean read,
        @JsonProperty("write") boolean write,
        @JsonProperty("admin") boolean admin
) {
    @JsonCreator
    public UserPolicy {}

    public static UserPolicy create(String userId, String projectId, boolean read, boolean write, boolean admin) {
        return new UserPolicy(userId, projectId, read, write, admin);
    }

    public static UserPolicy create(String userId, String projectId) {
        return new UserPolicy(userId, projectId, false, false, false);
    }

    public static UserPolicy create(User user, String projectId) {
        return UserPolicy.create(user.id, projectId);
    }

}
