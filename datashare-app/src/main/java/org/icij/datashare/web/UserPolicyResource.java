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
import org.icij.datashare.RecordNotFoundException;
import org.icij.datashare.session.UserPolicyVerifier;
import org.icij.datashare.user.Role;
import org.icij.datashare.user.UserPolicy;

import java.util.Arrays;
import java.util.stream.Stream;

import static net.codestory.http.constants.HttpStatus.NO_CONTENT;
import static net.codestory.http.payload.Payload.badRequest;
import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/policies")
public class UserPolicyResource {
    private final UserPolicyVerifier userPolicyVerifier;

    @Inject
    public UserPolicyResource(UserPolicyVerifier userPolicyVerifier) {
        this.userPolicyVerifier = userPolicyVerifier;
    }

    private static Role[] getRoles(String commaSeparatedRoles) throws IllegalArgumentException {
        return Arrays.stream(commaSeparatedRoles.split(","))
                .map(String::trim)
                .map(Role::valueOf)
                .toArray(Role[]::new);
    }

    @Operation(description = "Get a policy regarding a user a project ")
    @ApiResponse(responseCode = "200", description = "Policy retrieved successfully.")
    @Get("/?userId=:userId&projectId=:projectId")
    public Payload getUserPoliciesByUserByProject(
            @Parameter(name = "userId", description = "User ID", in = ParameterIn.QUERY) String userId,
            @Parameter(name = "projectId", description = "Project ID", in = ParameterIn.QUERY) String projectId,
            Context context) {

        try {
            if (userId == null && projectId == null) {
                Stream<UserPolicy> userPolicies = userPolicyVerifier.getUserPolicies();
                return new Payload(userPolicies).withCode(200);
            }
            return userPolicyVerifier.getUserPolicyByProject(userId, projectId).map(Payload::new).orElseGet(Payload::notFound);
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        }
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
            Role[] roles = getRoles(commaSeparatedRoles);
            return userPolicyVerifier.saveUserPolicy(userId, projectId, roles) ? ok() : badRequest();
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
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
        try {
            userPolicyVerifier.deleteUserPolicy(userId, projectId);
            return new Payload(NO_CONTENT);
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        }
    }

}
