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
@Prefix("/api/policies")
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
    }

    private void userExists(String name) {
        if (name == null || name.isBlank()) {
            throw new BlankParameterException("User cannot be null or blank");
        }
        if (repository.getUser(name) == null) {
            throw new RecordNotFoundException(UserInventory.class, name);
        }
    }

    /*
    Instance API
     */
    @Operation(description = "Get all instance policies",
            parameters = {
                    @Parameter(name = "from", description = "if not provided it starts from 0", in = ParameterIn.QUERY),
                    @Parameter(name = "to", description = "if not provided all queries are returned from the \"from\" parameter", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "Instance policies retrieved successfully.")
    @Get()
    public Payload getInstancePolicies(
            Context context) {
        String user = context.query().get("user");
        int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
        int to = Integer.parseInt(ofNullable(context.get("to")).orElse("0"));
        try {
            if (user != null) {
                return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(user).stream(), from, to));
            }
            return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions().stream(), from, to));
        } catch (BlankParameterException e) {
            return Payload.badRequest();
        } catch (RecordNotFoundException e) {
            return new Payload(WebResponse.fromStream(Stream.empty(), from, to));
        }
    }


    @Operation(description = "Remove an instance policy for a given user with a given role.")
    @ApiResponse(responseCode = "200", description = "Policy removed successfully.")
    @Delete("")
    public Payload removeInstancePolicy(
            Context context) {
        String user = context.query().get("user");
        String role = context.query().get("role");
        try {
            userExists(user);
            authorizer.deleteRoleForUserInInstance(user, Role.valueOf(role));

            return new Payload(NO_CONTENT);
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        } catch (BlankParameterException e) {
            return new Payload(e).withCode(Payload.badRequest().code());
        } catch (IllegalArgumentException e) {
            return new Payload("Invalid role in input: " + role).withCode(Payload.badRequest().code());
        }
    }

    @Operation(description = "Upsert a policy regarding a user a project a domain and its role.")
    @ApiResponse(responseCode = "200", description = "Policy added successfully.")
    @Put("")
    public Payload saveInstancePolicy(
            Context context) {
        String user = context.query().get("user");
        String role = context.query().get("role");
        try {
            userExists(user);
            authorizer.updateRoleForUserInDomain(user, Role.valueOf(role), Domain.of("*"));
            return ok();
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        } catch (BlankParameterException e) {
            return new Payload(e).withCode(Payload.badRequest().code());
        } catch (IllegalArgumentException e) {
            return new Payload("Invalid role in input: " + role).withCode(Payload.badRequest().code());
        }
    }
    /*
    DOMAIN api
     */
    @Operation(description = "Get a policies by domain regarding a user a project",
            parameters = {
                    @Parameter(name = "from", description = "if not provided it starts from 0", in = ParameterIn.QUERY),
                    @Parameter(name = "to", description = "if not provided all queries are returned from the \"from\" parameter", in = ParameterIn.QUERY),
                    @Parameter(name = "user", description = "user name to filter policies", in = ParameterIn.QUERY)
            }
    )

    @ApiResponse(responseCode = "200", description = "Domain policies retrieved successfully.")
    @Get("/:domain")
    public Payload getDomainPolicies(
            @Parameter(name = "domain", description = "Domain name", in = ParameterIn.PATH) String domain,
            Context context) {
        String user = context.query().get("user");
        int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
        int to = Integer.parseInt(ofNullable(context.get("to")).orElse("0"));
        try {
            domainIsPresent(domain);
            if (user != null) {
                return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(user, Domain.of(domain)).stream(), from, to));
            }
            return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(Domain.of(domain)).stream(), from, to));
        } catch (BlankParameterException e) {
            return Payload.badRequest();
        } catch (RecordNotFoundException e) {
            return Payload.notFound();
        }
    }

    @Operation(description = "Remove a domain policy for a given user with a given role.")
    @ApiResponse(responseCode = "200", description = "Policy removed successfully.")
    @Delete("/:domain")
    public Payload removeDomainPolicy(
            String domain,
            Context context) {
        String user = context.query().get("user");
        String role = context.query().get("role");
        try {
            domainIsPresent(domain);
            userExists(user);
            authorizer.deleteRoleForUserInDomain(user, Role.valueOf(role), Domain.of(domain));
            return new Payload(NO_CONTENT);
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        } catch (BlankParameterException e) {
            return new Payload(e).withCode(Payload.badRequest().code());
        } catch (IllegalArgumentException e) {
            return new Payload("Invalid role in input: " + role).withCode(Payload.badRequest().code());
        }
    }

    @Operation(description = "Upsert a policy regarding a user a project a domain and its role.")
    @ApiResponse(responseCode = "200", description = "Policy added successfully.")
    @Put("/:domain")
    public Payload saveDomainPolicy(
            String domain,
            Context context) {
        String user = context.query().get("user");
        String role = context.query().get("role");
        try {
            domainIsPresent(domain);
            userExists(user);
            authorizer.updateRoleForUserInDomain(user, Role.valueOf(role), Domain.of(domain));
            return ok();
        } catch (RecordNotFoundException e) {
            return new Payload(e).withCode(404);
        } catch (BlankParameterException e) {
            return new Payload(e).withCode(Payload.badRequest().code());
        } catch (IllegalArgumentException e) {
            return new Payload("Invalid role in input: " + role).withCode(Payload.badRequest().code());
        }
    }
    /*
    Project API
     */
    @Operation(description = "Get a policies by project in a given domain",
            parameters = {
                    @Parameter(name = "from", description = "if not provided it starts from 0", in = ParameterIn.QUERY),
                    @Parameter(name = "to", description = "if not provided all queries are returned from the \"from\" parameter", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "Project policies retrieved successfully.")
    @Get("/:domain/:project")
    public Payload getProjectPolicies(
            @Parameter(name = "domain", description = "Domain name", in = ParameterIn.PATH) String domain,
            @Parameter(name = "project", description = "Project name", in = ParameterIn.PATH) String project,
            Context context) {
        String user = context.query().get("user");
        int from = Integer.parseInt(ofNullable(context.get("from")).orElse("0"));
        int to = Integer.parseInt(ofNullable(context.get("to")).orElse("0"));
        try {
            domainIsPresent(domain);
            projectExists(project);
            if (user != null) {
                return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(user, Domain.of(domain), project).stream(), from, to));
            }
            return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(Domain.of(domain), project).stream(), from, to));
        } catch (BlankParameterException e) {
            return Payload.badRequest();
        } catch (RecordNotFoundException e) {
            return Payload.notFound();
        }
    }

    @Operation(description = "Remove a project policy for a given user with a given role.")
    @ApiResponse(responseCode = "200", description = "Policy removed successfully.")
    @Delete("/:domain/:project")
    public Payload removeProjectPolicy(
            String domain,
            String project,
            Context context) {
        String user = context.query().get("user");
        String role = context.query().get("role");
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


    @Operation(description = "Upsert a policy regarding a user a project a domain and its role.")
    @ApiResponse(responseCode = "200", description = "Policy added successfully.")
    @Put("/:domain/:project")
    public Payload saveProjectPolicy(
            String domain,
            String project,
            Context context) {
        String user = context.query().get("user");
        String role = context.query().get("role");
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


    static class BlankParameterException extends IllegalArgumentException {
        public BlankParameterException(String parameterName) {
            super(parameterName + " cannot be null or blank");
        }
    }

}
