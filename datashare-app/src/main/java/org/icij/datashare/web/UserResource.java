package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.*;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import org.icij.datashare.Repository;
import org.icij.datashare.UserEvent;
import org.icij.datashare.UserEvent.Type;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Policy;
import org.icij.datashare.policies.Role;
import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.project.admin.ProjectGranted;
import org.icij.datashare.project.admin.ProjectNotFoundException;
import org.icij.datashare.project.admin.ProjectRevoked;
import org.icij.datashare.project.admin.WideRoleHeldException;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Project;
import org.icij.datashare.user.User;
import org.icij.datashare.user.admin.UserAdminService;
import org.icij.datashare.user.admin.UserCreateRequest;
import org.icij.datashare.user.admin.UserExistsException;
import org.icij.datashare.user.admin.UserFilter;
import org.icij.datashare.user.admin.UserListItem;
import org.icij.datashare.user.admin.RoleGranted;
import org.icij.datashare.user.admin.RoleRevoked;
import org.icij.datashare.user.admin.UserNotFoundException;
import org.icij.datashare.user.admin.UserUpdateRequest;
import org.icij.datashare.user.admin.ValidationException;
import org.icij.datashare.utils.PayloadFormatter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static java.lang.Boolean.parseBoolean;
import static java.util.Objects.isNull;
import static net.codestory.http.payload.Payload.ok;
import static org.icij.datashare.db.tables.UserHistory.USER_HISTORY;

@Singleton
@Prefix("/api/users")
public class UserResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserResource.class);
    private static final Domain INSTANCE_SCOPE = Domain.of("*");
    private final Repository repository;
    private final Authorizer authorizer;
    private final UserAdminService userAdminService;
    private final ProjectAdminService projectAdminService;

    private List<Project> getDatashareUserProjects(DatashareUser datashareUser) {
        List<String> projectNames = datashareUser.getProjectNames();
        List<Project> projects = datashareUser.getProjects();
        List<Project> repositoryProjects = repository.getProjects(projectNames);
        return projects.stream()
                       .map(project -> repositoryProjects.stream().filter(p -> p.name.equals(project.name)).findFirst()
                                                         .orElse(project)).collect(Collectors.toList());
    }

    // An instance or domain admin grant already authorizes every project of that scope via the
    // wildcard Casbin role; this only keeps the user's inventory (groups_by_applications.datashare,
    // what the UI lists for them) in sync with that, so they actually see what they're authorized
    // to access instead of whatever project-specific rows they happened to hold before.
    // Callers run it before the Casbin write: a failure then leaves the role unchanged, so a retry
    // redoes both instead of hitting the noop path with a stale inventory.
    //TODO #DOMAIN: scope to the projects of `domain` once projects carry a domain and domains are
    // operational; every project is in the single operational domain today.
    private void syncProjectInventoryForWideRole(String userId, boolean add) {
        List<String> projectNames = repository.getProjects().stream().map(p -> p.name).collect(Collectors.toList());
        try {
            if (add) {
                projectAdminService.addProjectsToInventory(projectNames, userId);
            } else {
                projectAdminService.removeProjectsFromInventory(projectNames, userId);
            }
        } catch (org.icij.datashare.project.admin.UserNotFoundException e) {
            // Shouldn't happen: userId was already resolved above by userAdminService.get(userId).
            LOGGER.warn("user {} disappeared before its project inventory could be synced", userId, e);
        }
    }

    // Instance admin and default-domain admin authorize every project; other domains do not (yet).
    private static boolean coversEveryProject(Domain scope) {
        return INSTANCE_SCOPE.equals(scope) || Domain.DEFAULT.equals(scope);
    }

    /** Null when not an integer or negative. Zero is valid: {@code size=0} is a count-only page. */
    private static Integer parseNonNegativeInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // A wide role replaces what it covers: project roles, and domain admin under instance admin.
    private void deleteGrantsReplacedBy(User user, Domain scope) {
        if (coversEveryProject(scope)) {
            authorizer.deleteProjectRolesForUser(user);
        }
        if (INSTANCE_SCOPE.equals(scope)) {
            authorizer.deleteDomainRolesForUser(user);
        }
    }

    // Another grant still covering every project keeps the inventory full after this one is revoked.
    // A scope only ever holds its own role type (see grantRoleToUser), so any role there counts.
    private boolean holdsAnotherGrantCoveringEveryProject(User user, Domain scope) {
        return Stream.of(INSTANCE_SCOPE, Domain.DEFAULT).filter(other -> !other.equals(scope))
                     .anyMatch(other -> !authorizer.getRolesForUserInDomain(user, other).isEmpty());
    }

    @Inject
    public UserResource(Repository repository, Authorizer authorizer, UserAdminService userAdminService,
                        ProjectAdminService projectAdminService) {
        this.repository = repository;
        this.authorizer = authorizer;
        this.userAdminService = userAdminService;
        this.projectAdminService = projectAdminService;
    }

    @Operation(description = "Lists users. Optional scope: ?domain=X or ?domain=X&index=Y. " +
                             "Filters: uid (exact match, takes precedence over q), q (free-text on uid/name/email), " +
                             "noRole (true=include no-role users, false=exclude them). " +
                             "Sort: uid | email | name | role, desc=true for descending. Paginated with from/size.")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "invalid sort, from or size parameter")
    @ApiResponse(responseCode = "501", description = "store does not support listing")
    @Get("/admin")
    @Policy(role = Role.PROJECT_ADMIN)
    public Payload listUsers(Context context) {
        String uid = context.get("uid");
        String q = context.get("q");
        String domain = context.get("domain");
        String index = context.get("index");
        String sortParam = context.get("sort");
        boolean desc = Boolean.parseBoolean(context.get("desc"));
        Integer fromParam = parseNonNegativeInt(context.get("from"), 0);
        Integer sizeParam = parseNonNegativeInt(context.get("size"), 100);
        String noRoleParam = context.get("noRole");
        Boolean noRole = noRoleParam != null ? Boolean.parseBoolean(noRoleParam) : null;
        boolean isScoped = domain != null || index != null;

        // 0. Validate sort param early
        if (sortParam != null && !sortParam.isBlank() && !"uid".equalsIgnoreCase(sortParam) &&
            !"email".equalsIgnoreCase(sortParam) && !"name".equalsIgnoreCase(sortParam) &&
            !"role".equalsIgnoreCase(sortParam)) {
            return PayloadFormatter.error("sort must be one of: uid, email, name, role", HttpStatus.BAD_REQUEST);
        }

        if (fromParam == null) {
            return PayloadFormatter.error("from must be a non-negative integer", HttpStatus.BAD_REQUEST);
        }
        if (sizeParam == null) {
            return PayloadFormatter.error("size must be a non-negative integer", HttpStatus.BAD_REQUEST);
        }
        int from = fromParam;
        int size = sizeParam;

        // 1. Fetch users: an exact uid lookup, or all users (q pre-filtered via UserFilter.matches in UsersInDb)
        List<User> users;
        try {
            // listing fetches all matching users into memory; acceptable for admin-only endpoints with
            // bounded user counts. uid skips it: resolving one user must not scan the whole inventory.
            // getByIds answers with an empty list for an unknown uid, which is an empty page here,
            // not a 404: this is a list endpoint.
            users = uid != null && !uid.isBlank() ? userAdminService.getByIds(Set.of(uid)) :
                    userAdminService.list(new UserFilter(q), null, 0, Integer.MAX_VALUE).items;
        } catch (UnsupportedOperationException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.NOT_IMPLEMENTED);
        }

        // 2. Build userId -> rules map from Casbin
        Map<String, List<CasbinRule>> rulesByUserId =
                authorizer.getGroupPermissions().stream().collect(Collectors.groupingBy(CasbinRule::getV0));

        // 3. Build UserListItem stream: scope-filter permissions per user
        Stream<UserListItem> stream = users.stream().map(user -> {
            List<UserListItem.Permission> permissions = rulesByUserId.getOrDefault(user.id, List.of()).stream()
                                                                     .filter(r -> matchesScope(r.getV2(), domain,
                                                                                               index))
                                                                     .map(r -> new UserListItem.Permission(r.getV1(),
                                                                                                           r.getV2()))
                                                                     .collect(Collectors.toList());
            return new UserListItem(user.id, user.name, user.email, permissions);
        });

        // 4. Apply scope + noRole filter
        stream = stream.filter(item -> {
            if (!item.permissions().isEmpty())
                return true;
            if (noRole == null)
                return !isScoped;
            return noRole;
        });

        // 5. Sort
        if (sortParam != null && !sortParam.isBlank()) {
            Comparator<UserListItem> comparator;
            if ("uid".equalsIgnoreCase(sortParam)) {
                comparator = Comparator.comparing(UserListItem::uid, String.CASE_INSENSITIVE_ORDER);
            } else if ("email".equalsIgnoreCase(sortParam)) {
                comparator =
                        Comparator.comparing(UserListItem::email, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            } else if ("name".equalsIgnoreCase(sortParam)) {
                comparator =
                        Comparator.comparing(UserListItem::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            } else if ("role".equalsIgnoreCase(sortParam)) {
                comparator = Comparator.comparingInt(
                        item -> item.permissions().stream().mapToInt(p -> roleOrdinal(p.v1())).min()
                                    .orElse(Integer.MAX_VALUE));
            } else {
                return PayloadFormatter.error("sort must be one of: uid, email, name, role", HttpStatus.BAD_REQUEST);
            }
            if (desc)
                comparator = comparator.reversed();
            stream = stream.sorted(comparator);
        }

        // 6. Paginate
        return new Payload(WebResponse.fromStream(stream, from, size));
    }

    private static boolean matchesScope(String v2, String domain, String index) {
        if (domain == null && index == null)
            return true;
        String effectiveDomain = domain != null ? domain : Domain.DEFAULT.id();
        if ("*::*".equals(v2))
            return true;
        if (index != null) {
            return v2.equals(effectiveDomain + "::" + index) || v2.equals(effectiveDomain + "::*");
        }
        return v2.startsWith(effectiveDomain + "::");
    }

    private static int roleOrdinal(String roleName) {
        try {
            return Role.valueOf(roleName).ordinal();
        } catch (IllegalArgumentException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Null when the body cannot be parsed. fluent-http deserializes a route parameter before the
     * method runs, so taking the body in the signature turns a malformed one into a 500 carrying
     * the Java class name.
     */
    private <T> T extractOrNull(Context context, Class<T> type, String route) {
        try {
            return context.extract(type);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("malformed body on {}", route, e);
            return null;
        }
    }

    @Operation(description = "Creates a new user.", requestBody = @RequestBody(required = true,
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = UserCreateRequest.class))))
    @ApiResponse(responseCode = "201", description = "user created")
    @ApiResponse(responseCode = "400", description = "validation error or malformed body")
    @ApiResponse(responseCode = "409", description = "user already exists")
    @Policy(role = Role.INSTANCE_ADMIN)
    @Post
    public Payload createUser(Context context) {
        UserCreateRequest request = extractOrNull(context, UserCreateRequest.class, "POST /api/users");
        if (request == null) {
            return PayloadFormatter.error("malformed JSON body", HttpStatus.BAD_REQUEST);
        }
        try {
            return new Payload(userAdminService.create(request)).withCode(HttpStatus.CREATED);
        } catch (UserExistsException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.CONFLICT);
        } catch (ValidationException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = "Gets a user by userId (uid).",
            parameters = @Parameter(name = "userId", in = ParameterIn.PATH))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "user not found")
    @Policy(role = Role.INSTANCE_ADMIN)
    @Get("/admin/:userId")
    public Payload getUserByUid(String userId) {
        try {
            return new Payload(userAdminService.get(userId));
        } catch (UserNotFoundException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.NOT_FOUND);
        }
    }

    @Operation(description = "Updates a user. Omit any field to keep its current value.",
            parameters = @Parameter(name = "userId", in = ParameterIn.PATH), requestBody = @RequestBody(required = true,
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = UserUpdateRequest.class))))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "validation error or malformed body")
    @ApiResponse(responseCode = "404", description = "user not found")
    @Policy(role = Role.INSTANCE_ADMIN)
    @Put("/admin/:userId")
    public Payload updateUser(String userId, Context context) {
        UserUpdateRequest request = extractOrNull(context, UserUpdateRequest.class, "PUT /api/users/admin/:userId");
        if (request == null) {
            return PayloadFormatter.error("malformed JSON body", HttpStatus.BAD_REQUEST);
        }
        try {
            return new Payload(userAdminService.update(userId, request));
        } catch (UserNotFoundException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (ValidationException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    @Operation(description = "Deletes a user by userId. Idempotent: returns 204 even when the user does not exist.",
            parameters = @Parameter(name = "uid", in = ParameterIn.PATH))
    @ApiResponse(responseCode = "204", description = "user deleted or did not exist")
    @Policy(role = Role.INSTANCE_ADMIN)
    @Delete("/admin/:userId")
    public Payload deleteUser(String userId) {
        userAdminService.deleteIfExists(userId);
        return new Payload(204);
    }

    @Operation(description =
            "Grants a project role to a user. role query param must be one of admin|editor|member|visitor. " +
            "Set ifNotExists=true for the idempotent variant (no-op if the user already holds exactly that role).",
            parameters = {@Parameter(name = "userId", in = ParameterIn.PATH),
                    @Parameter(name = "index", in = ParameterIn.PATH),
                    @Parameter(name = "role", in = ParameterIn.QUERY),
                    @Parameter(name = "ifNotExists", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "invalid role")
    @ApiResponse(responseCode = "404", description = "user or project not found")
    @ApiResponse(responseCode = "409", description = "user holds an instance or domain admin role")
    @Policy(role = Role.PROJECT_ADMIN)
    @Put("/admin/:userId/index/:index")
    public Payload grantProjectToUser(String userId, String index, Context context) {
        boolean ifNotExists = Boolean.parseBoolean(context.get("ifNotExists"));
        try {
            Role role = Validators.projectRole(context.get("role"));
            ProjectGranted granted = ifNotExists ? projectAdminService.grantIfNotExists(index, userId, role) :
                                     projectAdminService.grant(index, userId, role);
            return new Payload(granted);
        } catch (Validators.InvalidValueException | org.icij.datashare.project.admin.ValidationException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (ProjectNotFoundException | org.icij.datashare.project.admin.UserNotFoundException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (WideRoleHeldException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.CONFLICT);
        }
    }

    @Operation(description = "Revokes every role a user holds on a project. " +
                             "Set ifExists=true for the idempotent variant (no-op if the user does not exist or holds no role).",
            parameters = {@Parameter(name = "userId", in = ParameterIn.PATH),
                    @Parameter(name = "index", in = ParameterIn.PATH),
                    @Parameter(name = "ifExists", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "user or project not found")
    @Policy(role = Role.PROJECT_ADMIN)
    @Delete("/admin/:userId/index/:index")
    public Payload revokeProjectFromUser(String userId, String index, Context context) {
        boolean ifExists = Boolean.parseBoolean(context.get("ifExists"));
        try {
            ProjectRevoked revoked = ifExists ? projectAdminService.revokeIfExists(index, userId) :
                                     projectAdminService.revoke(index, userId);
            return new Payload(revoked);
        } catch (ProjectNotFoundException | org.icij.datashare.project.admin.UserNotFoundException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.NOT_FOUND);
        }
    }

    @Operation(description = "Grants a domain or instance admin role to a user. " +
                             "role query param must be one of domain_admin|instance_admin. " +
                             "domain query param is optional (only relevant for domain_admin) and defaults to the default domain.",
            parameters = {@Parameter(name = "userId", in = ParameterIn.PATH),
                    @Parameter(name = "role", in = ParameterIn.QUERY),
                    @Parameter(name = "domain", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "invalid role")
    @ApiResponse(responseCode = "404", description = "user not found")
    @Policy(role = Role.INSTANCE_ADMIN)
    @Put("/admin/:userId/role")
    public Payload grantRoleToUser(String userId, Context context) {
        try {
            Role role = Validators.instanceOrDomainRole(context.get("role"));
            Domain domain = Domain.of(getStringValue(context.get("domain")).orElse(Domain.DEFAULT.id()));
            User user = userAdminService.get(userId);

            // A scope only ever holds the one role type it was granted with (an instance scope only ever
            // holds INSTANCE_ADMIN, a domain scope only ever holds DOMAIN_ADMIN), so the previous role at
            // that scope is either this same role, or none.
            Domain scope = role == Role.INSTANCE_ADMIN ? INSTANCE_SCOPE : domain;
            boolean alreadyGranted = authorizer.getRolesForUserInDomain(user, scope).contains(role.name());
            if (!alreadyGranted) {
                if (coversEveryProject(scope)) {
                    syncProjectInventoryForWideRole(userId, true);
                }
                authorizer.addRoleForUserInDomain(user, role, scope);
            }
            // Also when the role was already held, so a retry or a re-grant clears leftovers.
            deleteGrantsReplacedBy(user, scope);
            return new Payload(new RoleGranted(role, userId, alreadyGranted ? role : null, alreadyGranted));
        } catch (Validators.InvalidValueException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (UserNotFoundException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.NOT_FOUND);
        }
    }

    @Operation(description = "Revokes a domain or instance admin role from a user. " +
                             "role query param must be one of domain_admin|instance_admin. " +
                             "domain query param is optional (only relevant for domain_admin) and defaults to the default domain.",
            parameters = {@Parameter(name = "userId", in = ParameterIn.PATH),
                    @Parameter(name = "role", in = ParameterIn.QUERY),
                    @Parameter(name = "domain", in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "invalid role")
    @ApiResponse(responseCode = "404", description = "user not found")
    @Policy(role = Role.INSTANCE_ADMIN)
    @Delete("/admin/:userId/role")
    public Payload revokeRoleFromUser(String userId, Context context) {
        try {
            Role role = Validators.instanceOrDomainRole(context.get("role"));
            Domain domain = Domain.of(getStringValue(context.get("domain")).orElse(Domain.DEFAULT.id()));
            User user = userAdminService.get(userId);

            Domain scope = role == Role.INSTANCE_ADMIN ? INSTANCE_SCOPE : domain;
            boolean currentlyGranted = authorizer.getRolesForUserInDomain(user, scope).contains(role.name());
            if (currentlyGranted) {
                //TODO #DOMAIN: also consider domain admin grants on other domains once domains are operational.
                if (coversEveryProject(scope) && !holdsAnotherGrantCoveringEveryProject(user, scope)) {
                    syncProjectInventoryForWideRole(userId, false);
                }
                authorizer.deleteRoleForUserInDomain(user, role, scope);
            }
            return new Payload(new RoleRevoked(role, userId, currentlyGranted ? role : null, !currentlyGranted));
        } catch (Validators.InvalidValueException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (UserNotFoundException e) {
            return PayloadFormatter.error(e.getMessage(), HttpStatus.NOT_FOUND);
        }
    }

    @Operation(description = "Gets the user's session information.")
    @ApiResponse(responseCode = "200", description = "returns the user map", useReturnTypeSchema = true)
    @Get("/me")
    public Map<String, Object> getUser(Context context) {
        DatashareUser datashareUser = (DatashareUser) context.currentUser();
        Map<String, Object> details = datashareUser.getDetails();
        //TODO #DOMAIN: change Domain.DEFAULT to variable when domain are operational
        details.put("projects", getDatashareUserProjects(datashareUser));
        return details;
    }

    @Operation(description = "Gets the current user's permissions.")
    @ApiResponse(responseCode = "200", description = "returns the list of casbin rules for the current user",
            useReturnTypeSchema = true)
    @Get("/me/permissions")
    public List<CasbinRule> getUserPermissions(Context context) {
        DatashareUser datashareUser = (DatashareUser) context.currentUser();
        return authorizer.getGroupPermissions(User.localUser(datashareUser.id));
    }

    @Operation(description = "Preflight request for history")
    @ApiResponse(responseCode = "200", description = "returns 200 with OPTIONS, GET, PUT and DELETE")
    @Options("/me/history")
    public Payload getUserHistory(String userId) {
        return ok().withAllowMethods("OPTIONS", "GET", "PUT", "DELETE");
    }

    @Operation(description = "Gets the user's history by type", parameters = {
            @Parameter(name = "from", description = "the offset of the list, starting from 0", in = ParameterIn.QUERY),
            @Parameter(name = "size", description = "the number of element retrieved", in = ParameterIn.QUERY),
            @Parameter(name = "type", description = "string included in 'document' or 'search'",
                    in = ParameterIn.QUERY),
            @Parameter(name = "sort", description = "the name of the parameter to sort on (default: modificationDate)",
                    in = ParameterIn.QUERY),
            @Parameter(name = "desc", description = "the list is sorted in descending order (default: true)",
                    in = ParameterIn.QUERY), @Parameter(name = "projects",
            description = "projectIds separated by comma to filter by projects (default: none)",
            in = ParameterIn.QUERY)})
    @ApiResponse(responseCode = "200", description = "returns the user's list of events and the total number of events")
    @Get("/me/history?type=:type&from=:from&size=:size&sort=:sort&desc=:desc&projects=:projects")
    public Payload getUserHistory(String type, int from, int size, String sort, String desc, String projects,
                                  Context context) {
        DatashareUser user = (DatashareUser) context.currentUser();
        Type eventType = Type.valueOf(type.toUpperCase());
        String sortBy = getStringValue(sort).orElse(USER_HISTORY.MODIFICATION_DATE.getName());
        try {
            WebResponse<UserEvent> userEventWebResponse = new WebResponse<>(
                    repository.getUserHistory(user, eventType, from, size, sortBy, parseBooleanQueryArg(desc),
                                              parseProjectIdsQueryArg(projects)), from, size,
                    repository.getUserHistorySize(user, eventType, parseProjectIdsQueryArg(projects)));
            return new Payload(userEventWebResponse);
        } catch (IllegalArgumentException e) {
            return Payload.badRequest();
        }
    }

    private static Optional<String> getStringValue(String value) {
        return Optional.ofNullable(value).filter(Predicate.not(String::isBlank));
    }

    @NotNull
    private static String[] parseProjectIdsQueryArg(String projects) {
        return getStringValue(projects).isEmpty() ? new String[] {} : projects.trim().split(",");
    }

    private static boolean parseBooleanQueryArg(String desc) {
        return parseBoolean(desc) || getStringValue(desc).isEmpty() || !desc.equalsIgnoreCase("false");
    }

    @Operation(description = """
            Add or update an event to user's history. The event's type, the project ids and the uri are passed in the request body.
            
            To update the event's name, the eventId is required to retrieve the corresponding event.
            The project list related to the event is stored in database but is never queried (no filters on project).
            """)
    @ApiResponse(responseCode = "200", description = "returns 200 when event is added or updated.")
    @Put("/me/history")
    public Payload addToUserHistory(@Parameter(name = "query", description = "user history query to save",
            in = ParameterIn.QUERY) UserHistoryQuery query, Context context) {
        if (!isNull(query.eventId)) {
            boolean updated =
                    repository.renameSavedSearch((DatashareUser) context.currentUser(), query.eventId, query.name);
            return updated ? ok() : new Payload(400);
        }
        repository.addToUserHistory(query.projects,
                                    new UserEvent((DatashareUser) context.currentUser(), query.type, query.name,
                                                  query.uri));
        return ok();
    }

    @Operation(description = "Delete user history by type.")
    @ApiResponse(responseCode = "204", description = "Returns 204 (No Content) : idempotent",
            useReturnTypeSchema = true)
    @Delete("/me/history?type=:type")
    public Payload deleteUserHistory(
            @Parameter(name = "type", description = "type of user history event", in = ParameterIn.QUERY) String type,
            Context context) {
        repository.deleteUserHistory((DatashareUser) context.currentUser(), Type.valueOf(type.toUpperCase()));
        return new Payload(204);
    }

    @Operation(description = "Preflight request for history")
    @ApiResponse(responseCode = "200", description = "returns OPTIONS and DELETE")
    @Options("/me/history/event")
    public Payload deleteUserEvent(String userId) {
        return ok().withAllowMethods("OPTIONS", "DELETE");
    }

    @Operation(description = "Delete user event by id.")
    @ApiResponse(responseCode = "204", description = "Returns 204 (No Content) : idempotent")
    @Delete("/me/history/event?id=:eventId")
    public Payload deleteUserEvent(@Parameter(name = "eventId", description = "user history event id to delete",
            in = ParameterIn.QUERY) String eventId, Context context) {
        repository.deleteUserHistoryEvent((DatashareUser) context.currentUser(), Integer.parseInt(eventId));
        return new Payload(204);
    }

    private static class UserHistoryQuery {
        final Type type;
        final List<Project> projects;
        final String name;
        final URI uri;
        final Integer eventId;

        @JsonCreator
        private UserHistoryQuery(@JsonProperty("type") String type, @JsonProperty("name") String name,
                                 @JsonProperty("projectIds") List<String> projectIds, @JsonProperty("uri") String uri,
                                 @JsonProperty("eventId") Integer id) {
            this.type = Type.valueOf(type);
            this.projects = projectIds == null ? Collections.emptyList() :
                            projectIds.stream().map(Project::project).collect(Collectors.toList());
            this.name = name;
            this.uri = uri == null ? null : URI.create(uri);
            this.eventId = id;
        }
    }
}
