package org.icij.datashare.user;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.text.indexing.IndexType;

/**
 * Domain model for a user permission on a project, matching the user_permission table.
 */

@IndexType("UserPermission")
public record UserPermission(
        @JsonProperty("userId") String userId,
        @JsonProperty("projectId") String projectId,
        @JsonProperty("read") boolean read,
        @JsonProperty("write") boolean write,
        @JsonProperty("admin") boolean admin
) {
    @JsonCreator
    public UserPermission {}

    public static UserPermission create(String userId, String projectId, boolean read, boolean write, boolean admin) {
        return new UserPermission(userId, projectId, read, write, admin);
    }
    public static UserPermission create(String userId, String projectId) {
        return new UserPermission(userId, projectId, false, false, false);
    }

    public static UserPermission create(User user, String projectId) {
        return UserPermission.create(user.id, projectId);
    }

}
