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
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;

import static java.util.Optional.ofNullable;
import static net.codestory.http.constants.HttpStatus.NO_CONTENT;
import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api/policies")
public class PolicyResource {
    private final Authorizer authorizer;

    @Inject
    public PolicyResource(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Operation(description = "Get a policies regarding a userId a projectId ",
            parameters = {
                    @Parameter(name = "from", description = "if not provided it starts from 0", in = ParameterIn.QUERY),
                    @Parameter(name = "to", description = "if not provided all queries are returned from the \"from\" parameter", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "Policy retrieved successfully.")
    @Get("/?userId=:userId&projectId=:projectId")
    public Payload getUserPoliciesByUserByProject(
            @Parameter(name = "userId", description = "User ID", in = ParameterIn.QUERY) String userId,
            @Parameter(name = "projectId", description = "Project ID", in = ParameterIn.QUERY) String projectId,
            Context context) {
        try {
            int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
            int to = Integer.parseInt(ofNullable(context.get("to")).orElse("0"));
            return new Payload(WebResponse.fromStream(authorizer.getRolesForUserInProject(userId, Domain.of(""), projectId).stream(), from, to));
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        }
    }


    @Operation(description = "Upsert a policy regarding a user a project and the permissions.")
    @ApiResponse(responseCode = "200", description = "Policy added successfully.")
    @Put("/?userId=:userId&projectId=:projectId&role=:role")
    public Payload saveUserPolicy(
            @Parameter(name = "userId", description = "User ID", in = ParameterIn.QUERY) String userId,
            @Parameter(name = "projectId", description = "Project ID", in = ParameterIn.QUERY) String projectId,
            @Parameter(name = "role", description = "User role", in = ParameterIn.QUERY) String role,
            Context context) {
        try {
            authorizer.updateRoleForUserInProject(userId, Role.valueOf(role), Domain.of(""), projectId);
            return ok();
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        } catch (IllegalArgumentException e) {
            return new Payload("Invalid role in input: " + role).withCode(400);
        }
    }


    @Operation(description = "Remove a policy from the current user.")
    @ApiResponse(responseCode = "200", description = "Policy removed successfully.")
    @Delete("/?userId=:userId&projectId=:projectId")
    public Payload removeUserPolicy(
            @Parameter(name = "userId", description = "User ID", in = ParameterIn.QUERY) String userId,
            @Parameter(name = "projectId", description = "Project ID", in = ParameterIn.QUERY) String projectId,
            @Parameter(name = "role", description = "User role", in = ParameterIn.QUERY) String role,
            Context context) {
        try {
            authorizer.deleteRoleForUserInProject(userId, Role.valueOf(role), Domain.of(""), projectId);
            return new Payload(NO_CONTENT);
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        }
    }

}
