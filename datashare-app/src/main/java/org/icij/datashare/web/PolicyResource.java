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
import org.icij.datashare.Repository;
import org.icij.datashare.db.tables.Project;
import org.icij.datashare.db.tables.UserInventory;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;

import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static net.codestory.http.constants.HttpStatus.NO_CONTENT;
import static net.codestory.http.payload.Payload.ok;

@Singleton
@Prefix("/api")
public class PolicyResource {
    private final Authorizer authorizer;
    private final Repository repository;

    @Inject
    public PolicyResource(Authorizer authorizer, Repository repository) {
        this.authorizer = authorizer;
        this.repository = repository;
    }

    private void domainIsPresent(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new BlankParameterException("Domain cannot be null or blank");
        }
    }

    private void projectExists(String name) {
        if (name == null || name.isBlank()) {
            throw new BlankParameterException("Project cannot be null or blank");
        }
        if (repository.getProject(name) == null) {
            throw new RecordNotFoundException(Project.class, name);
        }
        ;
    }

    private void userExists(String name) {
        if (name == null || name.isBlank()) {
            throw new BlankParameterException("Project cannot be null or blank");
        }
        if (repository.getUser(name) == null) {
            throw new RecordNotFoundException(UserInventory.class, name);
        }
        ;
    }

    @Operation(description = "Get all instance policies",
            parameters = {
                    @Parameter(name = "from", description = "if not provided it starts from 0", in = ParameterIn.QUERY),
                    @Parameter(name = "to", description = "if not provided all queries are returned from the \"from\" parameter", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "Instance policies retrieved successfully.")
    @Get("/policies?user=:user")
    public Payload getInstancePolicies(
            @Parameter(name = "user", description = "User name", in = ParameterIn.QUERY) String user,
            Context context) {
        int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
        int to = Integer.parseInt(ofNullable(context.get("to")).orElse("0"));
        try {
            if (context.get("user") != null) {
                userExists(user);
                return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(user).stream(), from, to));
            }
            return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions().stream(), from, to));
        } catch (BlankParameterException e) {
            return Payload.badRequest();
        } catch (RecordNotFoundException e) {
            return new Payload(WebResponse.fromStream(Stream.empty(), from, to));
        }
    }

    @Operation(description = "Get a policies by domain regarding a user a project",
            parameters = {
                    @Parameter(name = "from", description = "if not provided it starts from 0", in = ParameterIn.QUERY),
                    @Parameter(name = "to", description = "if not provided all queries are returned from the \"from\" parameter", in = ParameterIn.QUERY),
                    @Parameter(name = "user", description = "user name to filter policies", in = ParameterIn.QUERY)
            }
    )

    @ApiResponse(responseCode = "200", description = "Domain policies retrieved successfully.")
    @Get("/policies/:domain?user=:user")
    public Payload getDomainPolicies(
            @Parameter(name = "domain", description = "Domain name", in = ParameterIn.PATH) String domain,
            @Parameter(name = "user", description = "User name", in = ParameterIn.QUERY) String user,
            Context context) {
        int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
        int to = Integer.parseInt(ofNullable(context.get("to")).orElse("0"));
        try {
            domainIsPresent(domain);
            if (context.get("user") != null) {
                return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(user, Domain.of(domain)).stream(), from, to));
            }
            return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(Domain.of(domain)).stream(), from, to));
        } catch (BlankParameterException e) {
            return Payload.badRequest();
        } catch (RecordNotFoundException e) {
            return Payload.notFound();
        }
    }

    @Operation(description = "Get a policies by project in a given domain",
            parameters = {
                    @Parameter(name = "from", description = "if not provided it starts from 0", in = ParameterIn.QUERY),
                    @Parameter(name = "to", description = "if not provided all queries are returned from the \"from\" parameter", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "Policy retrieved successfully.")
    @Get("/policies/:domain/:project?user=:user")
    public Payload getProjectPolicies(
            @Parameter(name = "domain", description = "Domain name", in = ParameterIn.QUERY) String domain,
            @Parameter(name = "project", description = "Project name", in = ParameterIn.QUERY) String project,
            @Parameter(name = "user", description = "User name", in = ParameterIn.QUERY) String user,
            Context context) {
        try {
            domainIsPresent(domain);
            projectExists(project);
            int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
            int to = Integer.parseInt(ofNullable(context.get("to")).orElse("0"));
            if (context.get("user") != null) {
                return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(user, Domain.of(domain), project).stream(), from, to));
            }
            return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(Domain.of(domain), project).stream(), from, to));
        } catch (BlankParameterException e) {
            return Payload.badRequest();
        } catch (RecordNotFoundException e) {
            return Payload.notFound();
        }
    }

    @Operation(description = "Upsert a policy regarding a user a project and the permissions.")
    @ApiResponse(responseCode = "200", description = "Policy added successfully.")
    @Put("/policies?user=:user&domain=:domain&project=:project&role=:role")
    public Payload saveUserPolicy(
            @Parameter(name = "user", description = "User ID", in = ParameterIn.QUERY) String user,
            @Parameter(name = "domain", description = "Domain name", in = ParameterIn.QUERY) String domain,
            @Parameter(name = "project", description = "Project ID", in = ParameterIn.QUERY) String project,
            @Parameter(name = "role", description = "User role", in = ParameterIn.QUERY) String role,
            Context context) {
        try {
            domainIsPresent(domain);
            projectExists(project);
            userExists(user);
            authorizer.updateRoleForUserInProject(user, Role.valueOf(role), Domain.of(domain), project);
            return ok();
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        } catch (BlankParameterException e) {
            return new Payload(e).withCode(Payload.badRequest().code());
        } catch (IllegalArgumentException e) {
            return new Payload("Invalid role in input: " + role).withCode(Payload.badRequest().code());
        }
    }

    @Operation(description = "Remove a policy from the current user.")
    @ApiResponse(responseCode = "200", description = "Policy removed successfully.")
    @Delete("/policies?domain=:domain&user=:user&project=:project&role=:role")
    public Payload removeUserPolicy(
            @Parameter(name = "domain", description = "Domain name", in = ParameterIn.PATH) String domain,
            @Parameter(name = "user", description = "User ID", in = ParameterIn.QUERY) String user,
            @Parameter(name = "project", description = "Project ID", in = ParameterIn.QUERY) String project,
            @Parameter(name = "role", description = "User role", in = ParameterIn.QUERY) String role,
            Context context) {
        try {
            domainIsPresent(domain);
            projectExists(project);
            userExists(user);
            authorizer.deleteRoleForUserInProject(user, Role.valueOf(role), Domain.of(domain), project);

            return new Payload(NO_CONTENT);
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        } catch (BlankParameterException e) {
            return new Payload(e).withCode(Payload.badRequest().code());
        } catch (IllegalArgumentException e) {
            return new Payload("Invalid role in input: " + role).withCode(Payload.badRequest().code());
        }
    }

    static class BlankParameterException extends IllegalArgumentException {
        public BlankParameterException(String parameterName) {
            super(parameterName + " cannot be null or blank");
        }
    }

}
