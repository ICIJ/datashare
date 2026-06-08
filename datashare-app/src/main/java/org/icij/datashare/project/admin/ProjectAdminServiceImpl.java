package org.icij.datashare.project.admin;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.io.FileUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.utils.DataDirVerifier;
import org.icij.datashare.policies.Authorizer;
import org.icij.datashare.policies.CasbinRule;
import org.icij.datashare.policies.Domain;
import org.icij.datashare.policies.Role;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.UsersIdProviderCache;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class ProjectAdminServiceImpl implements ProjectAdminService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectAdminServiceImpl.class);
    private static final String DEFAULT_ALLOW_FROM_MASK = "*.*.*.*";
    // Keys into User.details: per-application membership lists. The "datashare"
    // entry under "groups_by_applications" is the load-bearing projection used
    // by the UI/API to list a user's projects.
    private static final String GROUPS_BY_APPLICATIONS = "groups_by_applications";
    private static final String DATASHARE_APP = "datashare";
    // Lower ordinal == higher tier in Role enum (INSTANCE_ADMIN=0, ..., NONE=6).
    private static final Comparator<Role> ROLE_BY_TIER = Comparator.comparingInt(Enum::ordinal);

    private final Repository repository;
    private final Indexer indexer;
    private final Authorizer authorizer;
    private final DocumentCollectionFactory<Path> documentCollectionFactory;
    private final PropertiesProvider propertiesProvider;
    private final UsersIdProviderCache usersWritable;

    @Inject
    public ProjectAdminServiceImpl(Repository repository,
                                   Indexer indexer,
                                   Authorizer authorizer,
                                   DocumentCollectionFactory<Path> documentCollectionFactory,
                                   PropertiesProvider propertiesProvider,
                                   UsersIdProviderCache usersWritable) {
        this.repository = repository;
        this.indexer = indexer;
        this.authorizer = authorizer;
        this.documentCollectionFactory = documentCollectionFactory;
        this.propertiesProvider = propertiesProvider;
        this.usersWritable = usersWritable;
    }

    @Override
    public ProjectCreated create(ProjectCreateRequest request)
            throws ProjectExistsException, ValidationException, IOException {
        validate(request);
        if (repository.getProject(request.name()) != null) {
            throw new ProjectExistsException(request.name());
        }
        return persist(request);
    }

    @Override
    public ProjectCreated createIfNotExists(ProjectCreateRequest request)
            throws ValidationException, IOException {
        validate(request);
        Project existing = repository.getProject(request.name());
        if (existing != null) {
            return toCreated(existing, false, true);
        }
        return persist(request);
    }

    @Override
    public ProjectStats stats(String name, boolean includeIndexCount) throws ProjectNotFoundException, IOException {
        if (repository.getProject(name) == null) {
            throw new ProjectNotFoundException(name);
        }
        OptionalLong indexedDocuments = includeIndexCount
                ? OptionalLong.of(indexer.count(name))
                : OptionalLong.empty();
        int memberCount = (int) authorizer
                .getGroupPermissions(Domain.DEFAULT, name)
                .stream()
                .map(CasbinRule::getV0)
                .distinct()
                .count();
        return new ProjectStats(name, indexedDocuments, memberCount);
    }

    @Override
    public ProjectDeleted delete(String name, ProjectDeleteOptions options)
            throws ProjectNotFoundException, IOException {
        Project project = repository.getProject(name);
        if (project == null) {
            throw new ProjectNotFoundException(name);
        }
        return cascade(project, options);
    }

    @Override
    public ProjectDeleted deleteIfExists(String name, ProjectDeleteOptions options) throws IOException {
        Project project = repository.getProject(name);
        if (project == null) {
            return new ProjectDeleted(name, false, false, false, false, false, true);
        }
        return cascade(project, options);
    }

    @Override
    public ProjectGranted grant(String projectName, String userLogin, Role role)
            throws ProjectNotFoundException, UserNotFoundException, ValidationException {
        return doGrant(projectName, userLogin, role, false);
    }

    @Override
    public ProjectGranted grantIfNotExists(String projectName, String userLogin, Role role)
            throws ProjectNotFoundException, UserNotFoundException, ValidationException {
        return doGrant(projectName, userLogin, role, true);
    }

    private ProjectGranted doGrant(String projectName, String userLogin, Role role, boolean ifNotExists)
            throws ProjectNotFoundException, UserNotFoundException, ValidationException {
        validateProjectRole(role);
        Project project = requireProject(projectName);
        User user = requireUser(userLogin);
        List<Role> existing = readProjectRoles(user, project);

        // Replace-semantics make "noop" strict: any extra project roles would still
        // need clearing, so a noop is only safe when this exact role is the only one.
        if (ifNotExists && isExactlyThisRole(existing, role)) {
            return new ProjectGranted(projectName, userLogin, role, null, true);
        }

        User updated = appendProjectToInventory(user, projectName);
        swapCasbinRole(user, updated, project, existing, role);
        return new ProjectGranted(projectName, userLogin, role, highestRole(existing), false);
    }

    @Override
    public ProjectRevoked revoke(String projectName, String userLogin)
            throws ProjectNotFoundException, UserNotFoundException {
        Project project = requireProject(projectName);
        User user = requireUser(userLogin);
        return doRevoke(project, user, userLogin, readProjectRoles(user, project));
    }

    @Override
    public ProjectRevoked revokeIfExists(String projectName, String userLogin)
            throws ProjectNotFoundException {
        Project project = requireProject(projectName);

        // revokeIfExists swallows a missing user (unlike revoke); resolve via the
        // same SQL→Redis fallback used by requireUser so that OAuth2 users who
        // exist only in Redis are not silently skipped.
        User user = findUser(userLogin);
        if (user == null) {
            return noopRevoke(projectName, userLogin);
        }
        List<Role> existing = readProjectRoles(user, project);
        if (existing.isEmpty()) {
            return noopRevoke(projectName, userLogin);
        }
        return doRevoke(project, user, userLogin, existing);
    }

    private ProjectRevoked doRevoke(Project project, User user, String userLogin, List<Role> existing) {
        User updated = removeProjectFromInventory(user, project.getName());
        pruneCasbinRoles(user, updated, project, existing);
        return new ProjectRevoked(project.getName(), userLogin, existing, false);
    }

    private List<Role> readProjectRoles(User user, Project project) {
        return authorizer.getRolesForUserInProject(user, Domain.DEFAULT, project)
                .stream()
                .map(name -> {
                    try {
                        return Role.valueOf(name);
                    } catch (IllegalArgumentException e) {
                        // Casbin row holds a role string this codebase doesn't recognise
                        // (stale enum value, hand-edited row, custom role from an extension).
                        // Surface it in the log so operators can investigate, but don't
                        // break grant/revoke on an unparseable peer entry.
                        LOGGER.warn("ignoring unparseable role '{}' for user {} on project {}",
                                name, user.id, project.getName());
                        return null;
                    }
                })
                .filter(r -> r != null)
                .collect(Collectors.toList());
    }

    private static Role highestRole(List<Role> roles) {
        return roles.stream().min(ROLE_BY_TIER).orElse(null);
    }

    private static void validateProjectRole(Role role) throws ValidationException {
        if (role == null || (role != Role.PROJECT_ADMIN && role != Role.PROJECT_EDITOR
                && role != Role.PROJECT_MEMBER && role != Role.PROJECT_VISITOR)) {
            throw new ValidationException("role", "role must be a PROJECT_* role");
        }
    }

    // Existence guards: callers either get a non-null entity or a typed not-found
    // exception. Keeps grant/revoke flows linear instead of nested null checks.

    private Project requireProject(String name) throws ProjectNotFoundException {
        Project project = repository.getProject(name);
        if (project == null) {
            throw new ProjectNotFoundException(name);
        }
        return project;
    }

    private User findUser(String login) {
        User user = repository.getUser(login);
        if (user == null) {
            // Fall back to the configured auth provider (e.g. UsersInRedis).
            // DatashareUser extends User, so the cast is safe for all bundled providers.
            net.codestory.http.security.User httpUser = usersWritable.find(login);
            if (httpUser instanceof User) {
                user = (User) httpUser;
            }
        }
        return user;
    }

    private User requireUser(String login) throws UserNotFoundException {
        User user = findUser(login);
        if (user == null) {
            if (!(usersWritable instanceof UsersIdProviderCache)) {
                // usersWritable didn't cover Redis: an OAuth2 user whose session was created
                // before the first grant won't be found unless UsersInRedis is configured.
                throw new UserNotFoundException(login,
                        "OAuth2 sessions exist only in Redis; pass --authUsersProvider=UsersInRedis");
            }
            throw new UserNotFoundException(login);
        }
        return user;
    }

    private static boolean isExactlyThisRole(List<Role> existing, Role role) {
        return existing.size() == 1 && existing.get(0) == role;
    }

    private static ProjectRevoked noopRevoke(String projectName, String userLogin) {
        return new ProjectRevoked(projectName, userLogin, List.of(), true);
    }

    // Inventory mutations: return a fresh User with the per-application list
    // adjusted. The caller persists. Both helpers go through the same safe-cast
    // helpers so a stale or hand-edited details shape doesn't ClassCastException.

    private User appendProjectToInventory(User user, String projectName) {
        Map<String, Object> newDetails = new HashMap<>(user.details);
        Map<String, Object> apps = safeStringKeyedMapOf(newDetails.get(GROUPS_BY_APPLICATIONS));
        List<String> currentProjects = safeStringListOf(apps.get(DATASHARE_APP));
        if (!currentProjects.contains(projectName)) {
            currentProjects.add(projectName);
        }
        apps.put(DATASHARE_APP, currentProjects);
        newDetails.put(GROUPS_BY_APPLICATIONS, apps);
        User updated = new User(user.id, user.name, user.email, user.provider, newDetails);
        repository.save(updated);
        usersWritable.saveOrUpdate(new DatashareUser(updated));
        return updated;
    }

    private User removeProjectFromInventory(User user, String projectName) {
        Map<String, Object> newDetails = new HashMap<>(user.details);
        Map<String, Object> apps = safeStringKeyedMapOf(newDetails.get(GROUPS_BY_APPLICATIONS));
        List<String> currentProjects = safeStringListOf(apps.get(DATASHARE_APP));
        currentProjects.remove(projectName);
        apps.put(DATASHARE_APP, currentProjects);
        newDetails.put(GROUPS_BY_APPLICATIONS, apps);
        User updated = new User(user.id, user.name, user.email, user.provider, newDetails);
        repository.save(updated);
        usersWritable.saveOrUpdate(new DatashareUser(updated));
        return updated;
    }

    // Casbin operations with compensating-write rollback. Inventory-first /
    // Casbin-second is the load-bearing order (see ADR in spec): a Casbin write
    // without an inventory entry is the only end-state we cannot self-heal from.
    // If Casbin throws, restore the pre-mutation snapshot of the user row.

    private void swapCasbinRole(User original, User updated, Project project,
                                List<Role> existing, Role newRole) {
        casbinWithRollback(original, () -> {
            for (Role r : existing) {
                authorizer.deleteRoleForUserInProject(updated, r, Domain.DEFAULT, project);
            }
            authorizer.addRoleForUserInProject(updated, newRole, Domain.DEFAULT, project);
        });
    }

    private void pruneCasbinRoles(User original, User updated, Project project, List<Role> existing) {
        casbinWithRollback(original, () -> {
            for (Role r : existing) {
                authorizer.deleteRoleForUserInProject(updated, r, Domain.DEFAULT, project);
            }
        });
    }

    private void casbinWithRollback(User original, Runnable casbinAction) {
        try {
            casbinAction.run();
        } catch (RuntimeException casbinFailure) {
            try {
                repository.save(original);
            } catch (RuntimeException rollback) {
                casbinFailure.addSuppressed(rollback);
            }
            // Roll back the Redis inventory write that preceded the Casbin call.
            // Without this, SQL = original but Redis = updated: the user sees the
            // project in their dashboard but every Casbin enforce() returns false.
            try {
                usersWritable.saveOrUpdate(new DatashareUser(original));
            } catch (RuntimeException rollback) {
                casbinFailure.addSuppressed(rollback);
            }
            throw casbinFailure;
        }
    }

    private static Map<String, Object> safeStringKeyedMapOf(Object raw) {
        if (!(raw instanceof Map<?, ?> source)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                copy.put(key, entry.getValue());
            }
        }
        return copy;
    }

    private static List<String> safeStringListOf(Object raw) {
        if (!(raw instanceof List<?> source)) {
            return new ArrayList<>();
        }
        List<String> copy = new ArrayList<>();
        for (Object element : source) {
            if (element instanceof String s) {
                copy.add(s);
            }
        }
        return copy;
    }

    private ProjectDeleted cascade(Project project, ProjectDeleteOptions options) {
        // Per-step containment: each cleanup step swallows its own failures and
        // sets the corresponding bool to false. A partial-failure cascade is
        // worse than a continuing one -- operators can rerun with --if-exists
        // to converge once they have fixed the failing dependency, but a
        // half-deleted project where (say) the index is gone but queues are
        // intact is a sticky state if the cascade aborts mid-stream.
        String name = project.getName();

        boolean indexDeleted = !options.keepIndex()
                && runStep("index", name, () -> indexer.deleteAll(name));
        boolean dbDeleted = runStep("db", name, () -> repository.deleteAll(name));
        boolean queuesDeleted = runStep("queues", name, () -> deleteQueues(project));
        boolean reportMapDeleted = runStep("report map", name, () -> deleteReportMap(project));
        boolean artifactsDeleted = deleteArtifacts(name);

        return new ProjectDeleted(name, dbDeleted, indexDeleted,
                queuesDeleted, reportMapDeleted, artifactsDeleted, false);
    }

    @FunctionalInterface
    private interface CascadeStep {
        boolean run() throws Exception;
    }

    private static boolean runStep(String label, String project, CascadeStep step) {
        try {
            return step.run();
        } catch (Exception e) {
            LOGGER.error("cannot delete {} for project {}", label, project, e);
            return false;
        }
    }

    private boolean deleteQueues(Project project) {
        String name = project.getName();
        Properties properties = propertiesProvider.createOverriddenWith(
                Map.of(PropertiesProvider.DEFAULT_PROJECT_OPT, name));
        String defaultQueueName = properties.getOrDefault(
                PropertiesProvider.QUEUE_NAME_OPT, "extract:queue").toString();
        // Two lookups: legacy queues stored under the bare prefix
        // "extract:queue:<name>" and per-stage queues under
        // "extract:queue:<name>:*" (one per stage). Both must be drained on
        // delete; concatenating the streams covers them in a single pass.
        String queuePrefix = defaultQueueName + PropertiesProvider.QUEUE_SEPARATOR + name;
        String queuePattern = queuePrefix + PropertiesProvider.QUEUE_SEPARATOR + "*";
        // reduce() rather than allMatch() so every queue.delete() runs even
        // if an earlier one fails; we want the cascade-containment property,
        // not allMatch's short-circuit.
        return Stream.concat(
                        documentCollectionFactory.getQueues(queuePrefix, Path.class).stream(),
                        documentCollectionFactory.getQueues(queuePattern, Path.class).stream())
                .reduce(true, (acc, q) -> q.delete() && acc, Boolean::logicalAnd);
    }

    private boolean deleteReportMap(Project project) {
        String reportMapName = "extract:report:" + project.getName();
        return documentCollectionFactory.createMap(reportMapName).delete();
    }

    private boolean deleteArtifacts(String name) {
        return propertiesProvider.get(DatashareCliOptions.ARTIFACT_DIR_OPT)
                .map(dir -> {
                    try {
                        File projectArtifactDir = Path.of(dir).resolve(name).toFile();
                        FileUtils.deleteDirectory(projectArtifactDir);
                        return true;
                    } catch (IOException e) {
                        LOGGER.error("cannot delete project {} artifact dir", name, e);
                        return false;
                    }
                })
                .orElse(false);
    }

    private void validate(ProjectCreateRequest request) throws ValidationException {
        if (request.name() == null || !Project.NAME_PATTERN.matcher(request.name()).matches()) {
            throw new ValidationException("name",
                    "project name must match " + Project.NAME_REGEX);
        }
        if (request.allowFromMask() != null
                && !Project.ALLOW_FROM_MASK_PATTERN.matcher(request.allowFromMask()).matches()) {
            throw new ValidationException("allowFromMask",
                    "allow-from-mask must match " + Project.ALLOW_FROM_MASK_REGEX);
        }
        if (request.sourceUrl() != null) {
            validateUri(request.sourceUrl(), "sourceUrl");
        }
        if (request.logoUrl() != null) {
            validateUri(request.logoUrl(), "logoUrl");
        }
    }

    private static void validateUri(String value, String field) throws ValidationException {
        if (value.isBlank()) {
            throw new ValidationException(field, field + " must not be blank");
        }
        try {
            URI parsed = URI.create(value);
            if (parsed.getScheme() == null) {
                throw new ValidationException(field, field + " must include a scheme (e.g. https://)");
            }
        } catch (IllegalArgumentException e) {
            throw new ValidationException(field, field + " is not a valid RFC 3986 URI: " + e.getMessage());
        }
    }

    private ProjectCreated persist(ProjectCreateRequest request) throws IOException {
        String label = request.label() == null ? request.name() : request.label();
        Path sourcePath = request.sourcePath() == null
                ? new DataDirVerifier(propertiesProvider).path()
                : request.sourcePath();
        String allowFromMask = request.allowFromMask() == null
                ? DEFAULT_ALLOW_FROM_MASK
                : request.allowFromMask();
        // Auto-stamp on create. Backfill operators can override via explicit
        // request.creationDate()/updateDate(); otherwise both fields share the
        // same "now" timestamp (typical fresh-row pattern: a freshly created
        // project hasn't been updated yet, so creation == update).
        Date creationDate = request.creationDate() == null ? new Date() : request.creationDate();
        Date updateDate = request.updateDate() == null ? creationDate : request.updateDate();

        Project project = new Project(
                request.name(),
                label,
                request.description(),
                sourcePath,
                request.sourceUrl(),
                request.maintainerName(),
                request.publisherName(),
                request.logoUrl(),
                allowFromMask,
                creationDate,
                updateDate
        );

        if (!repository.save(project)) {
            throw new IllegalStateException("repository.save(Project) returned false for " + request.name());
        }

        boolean indexCreated = request.createIndex() && createIndexOrRollback(request.name());

        return toCreated(project, indexCreated, false);
    }

    private static ProjectCreated toCreated(Project project, boolean indexCreated, boolean noop) {
        return new ProjectCreated(
                project.getName(),
                project.getLabel(),
                project.getDescription(),
                project.getSourcePath(),
                project.getAllowFromMask(),
                project.getSourceUrl(),
                project.getMaintainerName(),
                project.getPublisherName(),
                project.getLogoUrl(),
                project.creationDate,
                project.updateDate,
                indexCreated,
                noop);
    }

    /**
     * Creates the ES index for {@code name} and compensates the DB row if the
     * call fails. A DB row pointing at a missing index is the worst end-state,
     * so on failure we delete the row and rethrow. Rollback failure is attached
     * to the original exception via {@code addSuppressed}.
     */
    private boolean createIndexOrRollback(String name) throws IOException {
        try {
            indexer.createIndex(name);
            return true;
        } catch (RuntimeException | IOException e) {
            try {
                repository.deleteAll(name);
            } catch (RuntimeException rollback) {
                e.addSuppressed(rollback);
            }
            if (e instanceof IOException io) throw io;
            throw (RuntimeException) e;
        }
    }
}
