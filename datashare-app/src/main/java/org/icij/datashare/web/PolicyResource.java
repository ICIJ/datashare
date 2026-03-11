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
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Repository;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Policy;
import org.icij.datashare.policies.Role;
import org.icij.datashare.policies.errors.InvalidValueException;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;

import static net.codestory.http.constants.HttpStatus.NO_CONTENT;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.policies.Authorizer.*;
import static org.icij.datashare.web.errors.BadRequestException.badRequestIfInvalid;
import static org.icij.datashare.web.errors.ForbiddenException.forbiddenIfNotEnoughRole;

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

    private static boolean isFilterValue(String value) {
        return value != null && !value.isBlank() && !value.equals("*");
    }

    private Domain domainExists(String name, boolean wildcard) {
        return badRequestIfInvalid(() -> requireDomain(name, wildcard));
    }

    private Role roleExists(String name) {
        return badRequestIfInvalid(() -> requireRole(name));
    }
    private Project projectExists(String name) {
        badRequestIfInvalid(() -> requireValue(name, false));
        return notFoundIfNull(repository.getProject(name));
    }

    private User userExists(String name) {
        badRequestIfInvalid(() -> requireValue(name, false));
        return notFoundIfNull(repository.getUser(name));
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
    @Policy(role = Role.INSTANCE_ADMIN)
    @Get()
    public Payload getInstancePolicies(Context context) {
        //user is a filter so we don't put hard validation on the param
        String user = context.query().get("user");
        WebResponseRange range = new WebResponseRange(context.get("from"), context.get("to"));
        // if we have a value, let's filter, else show all instance policies
        if (user != null && !user.isBlank()) {
            return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(User.localUser(user)).stream(), range));
        }
        return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions().stream(), range));
    }


    @Operation(description = "Remove an instance policy for a given user with a given role.")
    @ApiResponse(responseCode = "200", description = "Policy removed successfully.")
    @Policy(role = Role.INSTANCE_ADMIN)
    @Delete("")
    public Payload removeInstancePolicy(Context context) {
        User user = userExists(context.query().get("user"));
        Role role = roleExists(context.query().get("role"));
        try {
            authorizer.deleteRoleForUserInInstance(user, role);
            return new Payload(NO_CONTENT);
        } catch (InvalidValueException e) {
            return new Payload(e.getMessage()).withCode(HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = "Upsert a policy regarding a user a project a domain and its role.")
    @ApiResponse(responseCode = "200", description = "Policy added successfully.")
    @Policy(role = Role.INSTANCE_ADMIN)
    @Put("")
    public Payload saveInstancePolicy(
            Context context) {
        User user = userExists(context.query().get("user"));
        Role role = roleExists(context.query().get("role"));
        authorizer.updateRoleForUserInDomain(user, role, Domain.of("*"));
        return ok();
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
    @Policy(role = Role.DOMAIN_ADMIN)
    @Get("/:domain")
    public Payload getDomainPolicies(@Parameter(name = "domain", description = "Domain name", in = ParameterIn.PATH) String domain, Context context) {
        WebResponseRange range = new WebResponseRange(context.get("from"), context.get("to"));
        Domain domainValue = domainExists(domain, false);
            String userFilter = context.query().get("user");
            if (isFilterValue(userFilter)) {
                return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(User.localUser(userFilter), domainValue).stream(), range));
            }
        return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(domainValue).stream(), range));
    }

    @Operation(description = "Remove a domain policy for a given user with a given role.")
    @ApiResponse(responseCode = "200", description = "Policy removed successfully.")
    @Policy(role = Role.DOMAIN_ADMIN)
    @Delete("/:domain")
    public Payload removeDomainPolicy(String domain, Context context) {
            User user = userExists(context.query().get("user"));
        Role role = roleExists(context.query().get("role"));
        Domain domainValue = domainExists(domain, false);
            authorizer.deleteRoleForUserInDomain(user, role, domainValue);
            return new Payload(NO_CONTENT);
    }

    @Operation(description = "Upsert a policy regarding a user a project a domain and its role.")
    @ApiResponse(responseCode = "200", description = "Policy added successfully.")
    @Policy(role = Role.DOMAIN_ADMIN)
    @Put("/:domain")
    public Payload saveDomainPolicy(String domain, Context context) {
            User user = userExists(context.query().get("user"));
        Role role = roleExists(context.query().get("role"));
        Domain domainValue = domainExists(domain, false);
        User currentUser = requireUser((DatashareUser) context.currentUser());
        boolean allowed = authorizer.can(currentUser.id, domainValue, "*", role);
        forbiddenIfNotEnoughRole(allowed);
            authorizer.updateRoleForUserInDomain(user, role, domainValue);
            return ok();
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
    @Policy(role = Role.PROJECT_MEMBER)
    @Get("/:domain/:project")
    public Payload getProjectPolicies(
            @Parameter(name = "domain", description = "Domain name", in = ParameterIn.PATH) String domain,
            @Parameter(name = "project", description = "Project name", in = ParameterIn.PATH) String project,
            Context context) {
            String userFilter = context.query().get("user");
        WebResponseRange range = new WebResponseRange(context.get("from"), context.get("to"));

        Domain domainValue = domainExists(domain, false);
        Project projectValue = projectExists(project);

        if (isFilterValue(userFilter)) {
            return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(User.localUser(userFilter), domainValue, projectValue.getId()).stream(), range));
        }
        return new Payload(WebResponse.fromStream(authorizer.getGroupPermissions(domainValue, projectValue.getId()).stream(), range));
    }

    @Operation(description = "Remove a project policy for a given user with a given role.")
    @ApiResponse(responseCode = "200", description = "Policy removed successfully.")
    @Policy(role = Role.PROJECT_ADMIN)
    @Delete("/:domain/:project")
    public Payload removeProjectPolicy(String domain, String project, Context context) {
            User user = userExists(context.query().get("user"));
        Role role = roleExists(context.query().get("role"));
        Domain domainValue = domainExists(domain, false);
            Project projectValue = projectExists(project);
            authorizer.deleteRoleForUserInProject(user, role, domainValue, projectValue);

            return new Payload(NO_CONTENT);
    }


    @Operation(description = "Upsert a policy regarding a user a project a domain and its role.")
    @ApiResponse(responseCode = "200", description = "Policy added successfully.")
    @Policy(role = Role.PROJECT_EDITOR)
    @Put("/:domain/:project")
    public Payload saveProjectPolicy(String domain, String project, Context context) {
            User user = userExists(context.query().get("user"));
        Role role = roleExists(context.query().get("role"));
        Domain domainValue = domainExists(domain, false);
            Project projectValue = projectExists(project);

        boolean allowed = authorizer.can(requireUser((DatashareUser) context.currentUser()).id, domainValue, projectValue.getId(), role);
        forbiddenIfNotEnoughRole(allowed);
            authorizer.updateRoleForUserInProject(user, role, domainValue, projectValue);
            return ok();
    }


}
