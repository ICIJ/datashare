package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Delete;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.annotations.Put;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Repository;
import org.icij.datashare.session.UserPolicyVerifier;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicyRepository;

import java.net.URISyntaxException;
import java.util.Arrays;

import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/policies")
public class UserPolicyResource {
    private final UserPolicyVerifier userPolicyVerifier;


    @Inject
    public UserPolicyResource(UserPolicyRepository userPolicyRepository, Repository repository) throws URISyntaxException {
        userPolicyVerifier = UserPolicyVerifier.getInstance(userPolicyRepository, repository);
    }


    @Operation(description = "Get a policy regarding a user a project ")
    @ApiResponse(responseCode = "200", description = "Policy retrieved successfully.")
    @Get("/?userId=:userId&projectId=:projectId")
    public Payload getUserPoliciesByUserByProject(
            @Parameter(name = "userId", description = "User ID", in = ParameterIn.QUERY) String userId,
            @Parameter(name = "projectId", description = "Project ID", in = ParameterIn.QUERY) String projectId,
            Context context) {
        if (userId == null && projectId == null) {
            return new Payload(userPolicyVerifier.getUserPolicies()).withCode(200);
        }
        return userPolicyVerifier.getUserPolicyByProject(userId, projectId).map(Payload::new).orElseGet(Payload::notFound);
    }


    @Operation(description = "Upsert a policy regarding a user a project and the permissions.")
    @ApiResponse(responseCode = "200", description = "Policy added successfully.")
    @Put("/?userId=:userId&projectId=:projectId&roles=:comma_separated_roles")
    public Payload saveUserPolicy(
            @Parameter(name = "userId", description = "User ID", in = ParameterIn.QUERY) String userId,
            @Parameter(name = "projectId", description = "Project ID", in = ParameterIn.QUERY) String projectId,
            @Parameter(name = "comma_separated_roles", description = "User roles", in = ParameterIn.QUERY) String commaSeparatedRoles,
            Context context) {
        try {
            Role[] roles = Arrays.stream(commaSeparatedRoles.split(","))
                    .map(String::trim)
                    .map(Role::valueOf)
                    .toArray(Role[]::new);
            return userPolicyVerifier.saveUserPolicy(userId, projectId, roles) ? ok() : new Payload(400);
        } catch (IllegalArgumentException e) {
            return new Payload("Invalid role in input: " + commaSeparatedRoles).withCode(400);
        }
    }

    @Operation(description = "Remove a policy from the current user.")
    @ApiResponse(responseCode = "200", description = "Policy removed successfully.")
    @Delete("/?userId=:userId&projectId=:projectId")
    public Payload removeUserPolicy(
            @Parameter(name = "userId", description = "User ID", in = ParameterIn.QUERY) String userId,
            @Parameter(name = "projectId", description = "Project ID", in = ParameterIn.QUERY) String projectId,
            Context context) {
        boolean success = userPolicyVerifier.deleteUserPolicy(userId, projectId);
        return success ? ok() : new Payload(400);
    }

}
