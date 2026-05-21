package org.icij.datashare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.cli.CliExtensionService;
import org.icij.datashare.cli.Prompter;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.cli.spi.CliExtension;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.policies.Role;
import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.project.admin.ProjectCreateRequest;
import org.icij.datashare.project.admin.ProjectCreated;
import org.icij.datashare.project.admin.ProjectDeleteOptions;
import org.icij.datashare.project.admin.ProjectDeleted;
import org.icij.datashare.project.admin.ProjectExistsException;
import org.icij.datashare.project.admin.ProjectGranted;
import org.icij.datashare.project.admin.ProjectNotFoundException;
import org.icij.datashare.project.admin.ProjectStats;
import org.icij.datashare.tasks.ArtifactTask;
import org.icij.datashare.tasks.CreateNlpBatchesFromIndex;
import org.icij.datashare.tasks.CategorizeTask;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.tasks.DeduplicateTask;
import org.icij.datashare.tasks.EnqueueFromIndexTask;
import org.icij.datashare.tasks.ExtractNlpTask;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.ScanIndexTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.admin.UserAdminService;
import org.icij.datashare.user.admin.UserCreateRequest;
import org.icij.datashare.user.admin.UserCreated;
import org.icij.datashare.user.admin.UserExistsException;
import org.icij.datashare.user.admin.UserNotFoundException;
import org.icij.datashare.user.admin.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.PropertiesProvider.propertiesToMap;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.user.User.localUser;
import static org.icij.datashare.user.User.nullUser;

class CliApp {
    private static final Logger logger = LoggerFactory.getLogger(CliApp.class);

    // Single ObjectMapper shared across handlers for serializing JSON output.
    // Input is read from typed sibling properties, not parsed JSON, so this
    // mapper never sees untrusted strings.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Exit codes returned by the admin dispatchers (handleUserCreate, etc.).
    // Picocli itself owns exit code 2 (usage errors) and signals it before
    // dispatch, so it does not appear here. Kept in sync with the "code"
    // string in error() output; operators reading scripts can match either.
    static final int EXIT_SUCCESS    = 0;
    static final int EXIT_RUNTIME    = 1;
    static final int EXIT_NOT_FOUND  = 3;
    static final int EXIT_CONFLICT   = 4;
    static final int EXIT_VALIDATION = 5;

    static void start(Properties properties) throws Exception {
        ExtensionService extensionService = new ExtensionService(new PropertiesProvider(properties));
        process(extensionService, properties);
        process(new PluginService(new PropertiesProvider(properties), extensionService), properties);
        try (CommonMode commonMode = CommonMode.create(properties)) {
            List<CliExtension> extensions = CliExtensionService.getInstance().getExtensions();

            logger.info("found {} CLI extension(s)", extensions.size());
            if (extensions.size() == 1 && extensions.get(0).identifier().equals(properties.get("ext"))) {
                CliExtension extension = extensions.get(0);
                extension.init(commonMode::createChildInjector);
                extension.run(properties);
                System.exit(0);
            }
            runTaskWorker(commonMode, properties);
        }
    }

    private static void process(DeliverableService<?> deliverableService, Properties properties) throws IOException {
        String listPattern = deliverableService.getListOpt(properties);
        if (listPattern != null) {
            listPattern = listPattern.equalsIgnoreCase("true") ? ".*":listPattern;
            deliverableService.list(listPattern).forEach(DeliverablePackage::displayInformation);
        } else if(deliverableService.getInstallOpt(properties) != null) {
            deliverableService.downloadAndInstallFromCli(properties);
        } else if (deliverableService.getDeleteOpt(properties) != null) {
            deliverableService.deleteFromCli(properties);
        } else return;
        System.exit(0);
    }

    private static void runTaskWorker(CommonMode mode, Properties properties) throws Exception {
        TaskManager taskManager = mode.get(TaskManager.class);
        DatashareTaskFactory taskFactory = mode.get(DatashareTaskFactory.class);
        Indexer indexer = mode.get(Indexer.class);

        if (properties.getProperty(CREATE_INDEX_OPT) != null) {
            indexer.createIndex(properties.getProperty(CREATE_INDEX_OPT));
            System.exit(0);
        }

        if (properties.getProperty(CRE_API_KEY_OPT) != null) {
            String userName = properties.getProperty(CRE_API_KEY_OPT);
            String secretKey = taskFactory.createGenApiKey(localUser(userName)).call();
            logger.info("generated secret key for user {} (store it somewhere safe, datashare cannot retrieve it later): {}", userName, secretKey);
            System.exit(0);
        }

        if (properties.getProperty(GET_API_KEY_OPT) != null) {
            String userName = properties.getProperty(GET_API_KEY_OPT);
            String hashedKey = taskFactory.createGetApiKey(localUser(userName)).call();
            if ((hashedKey == null)) {
                logger.info("no user {} exists", userName);
            } else {
                logger.info("hashed key for user {} is {}", userName, hashedKey);
            }
            System.exit(0);
        }

        if (properties.getProperty(DEL_API_KEY_OPT) != null) {
            String userName = properties.getProperty(DEL_API_KEY_OPT);
            taskFactory.createDelApiKey(localUser(userName)).call();
            System.exit(0);
        }

        if (properties.getProperty(GRANT_ADMIN_OPT) != null) {
            String userName = properties.getProperty(GRANT_ADMIN_OPT);
            taskFactory.createGrantAdminPolicyTask(localUser(userName)).call();
            System.exit(0);
        }

        if (properties.getProperty(USER_CREATE_OPT) != null) {
            UserAdminService userAdminService = mode.get(UserAdminService.class);
            System.exit(handleUserCreate(userAdminService, properties));
        }

        if (properties.getProperty(USER_DELETE_OPT) != null) {
            UserAdminService userAdminService = mode.get(UserAdminService.class);
            System.exit(handleUserDelete(userAdminService, properties));
        }

        if (properties.getProperty(PROJECT_CREATE_OPT) != null) {
            ProjectAdminService projectAdminService = mode.get(ProjectAdminService.class);
            System.exit(handleProjectCreate(projectAdminService, properties));
        }

        if (properties.getProperty(PROJECT_DELETE_OPT) != null) {
            ProjectAdminService projectAdminService = mode.get(ProjectAdminService.class);
            System.exit(handleProjectDelete(projectAdminService, properties, Prompter::new));
        }

        if (properties.getProperty(PROJECT_GRANT_OPT) != null) {
            ProjectAdminService projectAdminService = mode.get(ProjectAdminService.class);
            System.exit(handleProjectGrant(projectAdminService, properties));
        }

        PipelineHelper pipeline = new PipelineHelper(new PropertiesProvider(properties));
        logger.info("executing {}", pipeline);
        if (pipeline.has(Stage.DEDUPLICATE)) {
            taskManager.startTask(DeduplicateTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.SCANIDX)) {
            taskManager.startTask(ScanIndexTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.SCAN)) {
            taskManager.startTask(ScanTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.INDEX)) {
            taskManager.startTask(IndexTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.ENQUEUEIDX)) {
            taskManager.startTask(EnqueueFromIndexTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.CATEGORIZE)) {
            taskManager.startTask(CategorizeTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.CREATENLPBATCHESFROMIDX)) {
            taskManager.startTask(CreateNlpBatchesFromIndex.class.getName(), nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.NLP)) {
            taskManager.startTask(ExtractNlpTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.ARTIFACT)) {
            taskManager.startTask(ArtifactTask.class, nullUser(), propertiesToMap(properties));
        }
        taskManager.awaitTermination(Integer.MAX_VALUE, SECONDS);
        taskManager.shutdown();
    }

    static int handleUserCreate(UserAdminService service, Properties properties) {
        String login = properties.getProperty(USER_CREATE_OPT);
        boolean json = Boolean.parseBoolean(properties.getProperty(USER_CREATE_JSON_OPT));
        boolean ifNotExists = Boolean.parseBoolean(properties.getProperty(USER_CREATE_IF_NOT_EXISTS_OPT));
        try {
            UserCreateRequest request = new UserCreateRequest(
                    login,
                    properties.getProperty(USER_CREATE_EMAIL_OPT),
                    properties.getProperty(USER_CREATE_NAME_OPT),
                    properties.getProperty(USER_CREATE_PASSWORD_OPT),
                    properties.getProperty(USER_CREATE_PROVIDER_OPT),
                    Validators.groups(properties.getProperty(USER_CREATE_GROUPS_OPT)));

            UserCreated created = ifNotExists ? service.createIfNotExists(request) : service.create(request);

            if (json) {
                System.out.println(MAPPER.writeValueAsString(Map.of(
                        "created", !created.noop(),
                        "noop", created.noop(),
                        "login", created.login(),
                        "email", created.email(),
                        "name", created.name(),
                        "provider", created.provider(),
                        "groups", created.groups())));
            } else if (created.noop()) {
                System.out.println("user '" + created.login() + "' already exists (no-op)");
            } else {
                System.out.println("created user '" + created.login() + "' (provider="
                        + created.provider() + ", groups=" + created.groups() + ")");
            }
            return EXIT_SUCCESS;
        } catch (UserExistsException e) {
            return error(e.getMessage(), "conflict", EXIT_CONFLICT, json);
        } catch (ValidationException e) {
            return error(e.getMessage(), "validation", EXIT_VALIDATION, json);
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", EXIT_RUNTIME, json);
        } finally {
            // Drop the password from the in-memory Properties as soon as the
            // request has been built, even on the error path.
            properties.remove(USER_CREATE_PASSWORD_OPT);
        }
    }

    static int handleUserDelete(UserAdminService service, Properties properties) {
        // Confirmation is handled in UserDeleteCommand before dispatch; this
        // path only runs when the user has already agreed (or passed --yes).
        String login = properties.getProperty(USER_DELETE_OPT);
        boolean json = Boolean.parseBoolean(properties.getProperty(USER_DELETE_JSON_OPT));
        boolean ifExists = Boolean.parseBoolean(properties.getProperty(USER_DELETE_IF_EXISTS_OPT));
        try {
            boolean removed = ifExists ? service.deleteIfExists(login) : service.delete(login);
            boolean noop = !removed;

            if (json) {
                System.out.println(MAPPER.writeValueAsString(Map.of(
                        "deleted", removed,
                        "noop", noop,
                        "login", login)));
            } else if (noop) {
                System.out.println("user '" + login + "' does not exist (no-op)");
            } else {
                System.out.println("deleted user '" + login + "'");
            }
            return EXIT_SUCCESS;
        } catch (UserNotFoundException e) {
            return error(e.getMessage(), "not_found", EXIT_NOT_FOUND, json);
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", EXIT_RUNTIME, json);
        }
    }

    /**
     * Outcome of the post-create auto-grant attempt.
     *
     * <p>{@code creator} is the resolved login (or {@code null} when no grant
     * was attempted). {@code status} captures why the attempt landed where it
     * did so the dispatcher can choose between silence, info, and warning
     * without re-deriving the reason from booleans.
     */
    private record GrantOutcome(String creator, GrantStatus status) {
        boolean granted() { return status == GrantStatus.GRANTED; }
        static GrantOutcome skipped() { return new GrantOutcome(null, GrantStatus.SKIPPED); }
    }

    private enum GrantStatus {
        /** No creator resolved, or no-op create (project already existed). */
        SKIPPED,
        /** Grant applied. */
        GRANTED,
        /** Resolved login does not exist in user_inventory. */
        USER_NOT_FOUND,
        /** Grant call threw. {@code GrantOutcome.creator} carries the failure detail. */
        ERROR
    }

    // Signature asymmetry note: handleProjectCreate takes only (service, properties)
    // because ProjectCreateCommand runs all of its operator prompts in the picocli
    // layer before dispatch -- by the time we land here, every field is already in
    // the typed sibling properties. handleProjectDelete on the other hand defers
    // the typed-name confirmation to the dispatcher (it needs the index count and
    // member count from service.stats() to show in the prompt, and the service is
    // only resolved in CliApp), so it takes a Supplier<Prompter> for test injection.
    static int handleProjectCreate(ProjectAdminService service, Properties properties) {
        boolean json = Boolean.parseBoolean(properties.getProperty(PROJECT_CREATE_JSON_OPT));
        boolean ifNotExists = Boolean.parseBoolean(properties.getProperty(PROJECT_CREATE_IF_NOT_EXISTS_OPT));
        try {
            ProjectCreateRequest request = buildCreateRequest(properties);
            ProjectCreated created;
            if (ifNotExists) {
                created = service.createIfNotExists(request);
            } else {
                created = service.create(request);
            }
            GrantOutcome grant = attemptAutoGrant(service, created, properties);
            if (json) {
                System.out.println(MAPPER.writeValueAsString(createResultMap(created, grant)));
            } else {
                emitCreateText(created, grant);
            }
            return EXIT_SUCCESS;
        } catch (ProjectExistsException e) {
            return error(e.getMessage(), "conflict", EXIT_CONFLICT, json);
        } catch (org.icij.datashare.project.admin.ValidationException e) {
            return error(e.getMessage(), "validation", EXIT_VALIDATION, json);
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", EXIT_RUNTIME, json);
        }
    }

    private static ProjectCreateRequest buildCreateRequest(Properties properties)
            throws org.icij.datashare.project.admin.ValidationException {
        String sourcePathOpt = properties.getProperty(PROJECT_CREATE_SOURCE_PATH_OPT);
        boolean noIndex = Boolean.parseBoolean(properties.getProperty(PROJECT_CREATE_NO_INDEX_OPT));
        Date creationDate = parseInstantOrNull(properties.getProperty(PROJECT_CREATE_CREATION_DATE_OPT), "creationDate");
        Date updateDate = parseInstantOrNull(properties.getProperty(PROJECT_CREATE_UPDATE_DATE_OPT), "updateDate");
        Path sourcePath = sourcePathOpt == null ? null : Path.of(sourcePathOpt);
        return new ProjectCreateRequest(
                properties.getProperty(PROJECT_CREATE_OPT),
                properties.getProperty(PROJECT_CREATE_LABEL_OPT),
                properties.getProperty(PROJECT_CREATE_DESCRIPTION_OPT),
                sourcePath,
                properties.getProperty(PROJECT_CREATE_ALLOW_FROM_MASK_OPT),
                properties.getProperty(PROJECT_CREATE_SOURCE_URL_OPT),
                properties.getProperty(PROJECT_CREATE_MAINTAINER_NAME_OPT),
                properties.getProperty(PROJECT_CREATE_PUBLISHER_NAME_OPT),
                properties.getProperty(PROJECT_CREATE_LOGO_URL_OPT),
                creationDate,
                updateDate,
                !noIndex);
    }

    /**
     * The CLI command pre-validates these strings as ISO-8601 before dispatch,
     * but the dispatcher is also called directly from tests and could in
     * theory be invoked from elsewhere. Surface bad input as a
     * {@code ValidationException} (exit 5) instead of an opaque runtime error.
     */
    private static Date parseInstantOrNull(String value, String field)
            throws org.icij.datashare.project.admin.ValidationException {
        if (value == null) return null;
        try {
            return Date.from(Instant.parse(value));
        } catch (java.time.format.DateTimeParseException e) {
            throw new org.icij.datashare.project.admin.ValidationException(
                    field, field + " must be ISO-8601 (e.g. 2026-05-15T10:00:00Z): " + e.getMessage());
        }
    }

    /**
     * Auto-grant PROJECT_ADMIN to the project's creator. Skipped for no-op
     * creates (the project already existed; the creator's grant state is the
     * operator's concern) and when no creator was resolved.
     *
     * <p>Warning policy: when {@code --creator} was set explicitly we warn on
     * any miss (the operator asked for a specific grant and we should tell
     * them it didn't happen). When the creator was resolved by falling back to
     * {@code --defaultUserName} we stay silent on a missing user -- typical
     * dev setups have a custom login that does not match the launcher's
     * default, and the noise isn't helpful.
     */
    private static GrantOutcome attemptAutoGrant(ProjectAdminService service,
                                                 ProjectCreated created,
                                                 Properties properties) {
        if (created.noop()) {
            return GrantOutcome.skipped();
        }
        String creator = resolveCreator(properties);
        if (creator == null) {
            return GrantOutcome.skipped();
        }
        boolean defaulted = properties.getProperty(PROJECT_CREATE_CREATOR_OPT) == null;
        try {
            service.grant(created.name(), creator, org.icij.datashare.policies.Role.PROJECT_ADMIN);
            return new GrantOutcome(creator, GrantStatus.GRANTED);
        } catch (org.icij.datashare.project.admin.UserNotFoundException e) {
            if (!defaulted) {
                System.err.println("warning: user '" + creator
                        + "' not found in inventory; auto-grant skipped");
            }
            return new GrantOutcome(creator, GrantStatus.USER_NOT_FOUND);
        } catch (Exception e) {
            System.err.println("warning: failed to grant PROJECT_ADMIN on '" + created.name()
                    + "' to '" + creator + "': " + e.getMessage());
            return new GrantOutcome(creator, GrantStatus.ERROR);
        }
    }

    private static Map<String, Object> createResultMap(ProjectCreated created, GrantOutcome grant) {
        return Map.ofEntries(
                Map.entry("created", !created.noop()),
                Map.entry("noop", created.noop()),
                Map.entry("name", created.name()),
                Map.entry("label", orEmpty(created.label())),
                Map.entry("description", orEmpty(created.description())),
                Map.entry("sourcePath", pathOrEmpty(created.sourcePath())),
                Map.entry("allowFromMask", orEmpty(created.allowFromMask())),
                Map.entry("sourceUrl", orEmpty(created.sourceUrl())),
                Map.entry("maintainerName", orEmpty(created.maintainerName())),
                Map.entry("publisherName", orEmpty(created.publisherName())),
                Map.entry("logoUrl", orEmpty(created.logoUrl())),
                Map.entry("creationDate", instantOrEmpty(created.creationDate())),
                Map.entry("updateDate", instantOrEmpty(created.updateDate())),
                Map.entry("indexCreated", created.indexCreated()),
                Map.entry("creator", orEmpty(grant.creator())),
                Map.entry("grantApplied", grant.granted()));
    }

    private static void emitCreateText(ProjectCreated created, GrantOutcome grant) {
        if (created.noop()) {
            System.out.println("project '" + created.name() + "' already exists (no-op)");
            return;
        }
        String indexBadge = created.indexCreated() ? "index=created" : "index=skipped";
        System.out.println("created project '" + created.name() + "' (label='"
                + orEmpty(created.label()) + "', source-path="
                + created.sourcePath() + ", allow-from-mask=" + created.allowFromMask()
                + ", " + indexBadge + ")");
        if (grant.granted()) {
            System.out.println("granted PROJECT_ADMIN on '" + created.name()
                    + "' to '" + grant.creator() + "'");
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String pathOrEmpty(Path p) {
        return p == null ? "" : p.toString();
    }

    private static String instantOrEmpty(Date d) {
        return d == null ? "" : d.toInstant().toString();
    }

    /**
     * Resolves the user login to auto-grant PROJECT_ADMIN to on `project create`.
     * Explicit {@code --creator} wins; otherwise we fall back to
     * {@code defaultUserName} (the launcher injects this with the OS user in
     * single-user dev setups). The service-level grant is lenient: if the
     * resolved login does not exist in {@code user_inventory}, a warning is
     * logged but the create still succeeds. Returns {@code null} to mean
     * "do not grant".
     */
    private static String resolveCreator(Properties properties) {
        String explicit = properties.getProperty(PROJECT_CREATE_CREATOR_OPT);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String defaultUser = properties.getProperty(DEFAULT_USER_NAME_OPT);
        return defaultUser == null || defaultUser.isBlank() ? null : defaultUser;
    }

    static int handleProjectDelete(ProjectAdminService service,
                                   Properties properties,
                                   Supplier<Prompter> prompterFactory) {
        String name = properties.getProperty(PROJECT_DELETE_OPT);
        boolean json = Boolean.parseBoolean(properties.getProperty(PROJECT_DELETE_JSON_OPT));
        boolean ifExists = Boolean.parseBoolean(properties.getProperty(PROJECT_DELETE_IF_EXISTS_OPT));
        boolean yes = Boolean.parseBoolean(properties.getProperty(PROJECT_DELETE_YES_OPT));
        boolean noInput = Boolean.parseBoolean(properties.getProperty(PROJECT_DELETE_NO_INPUT_OPT));
        boolean keepIndex = Boolean.parseBoolean(properties.getProperty(PROJECT_DELETE_KEEP_INDEX_OPT));
        ProjectDeleteOptions options = new ProjectDeleteOptions(keepIndex);

        try {
            ProjectStats stats = loadStatsOrNoop(service, name, options, ifExists, json);
            if (stats == null) return EXIT_SUCCESS; // not-found + --if-exists, already emitted

            if (!(yes || noInput) && !confirmDeletion(stats, name, prompterFactory)) {
                emitDeleteAborted(name, json);
                return EXIT_SUCCESS;
            }

            ProjectDeleted deleted = ifExists
                    ? service.deleteIfExists(name, options)
                    : service.delete(name, options);

            emitDeleteResult(deleted, options, json);
            return EXIT_SUCCESS;
        } catch (ProjectNotFoundException e) {
            return error(e.getMessage(), "not_found", EXIT_NOT_FOUND, json);
        } catch (Prompter.ValidationFailedException e) {
            // Confirmation prompt exhausted retries: treat as user-initiated abort.
            emitDeleteAborted(name, json);
            return EXIT_SUCCESS;
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", EXIT_RUNTIME, json);
        }
    }

    /**
     * Returns the project stats, or {@code null} when the project is missing and
     * {@code --if-exists} converts that into a successful no-op (which this
     * method emits before returning). Any other not-found case re-throws.
     */
    private static ProjectStats loadStatsOrNoop(ProjectAdminService service,
                                                String name,
                                                ProjectDeleteOptions options,
                                                boolean ifExists,
                                                boolean json)
            throws ProjectNotFoundException, IOException {
        try {
            return service.stats(name, !options.keepIndex());
        } catch (ProjectNotFoundException e) {
            if (ifExists) {
                emitDeleteNoop(name, json);
                return null;
            }
            throw e;
        }
    }

    /**
     * Shows the impact summary and reads the typed-name confirmation. Trusts
     * the prompter validator to throw on mismatch and bubble up as
     * {@link Prompter.ValidationFailedException} when retries exhaust; a clean
     * return from the prompter means the typed name matched.
     */
    private static boolean confirmDeletion(ProjectStats stats,
                                           String name,
                                           Supplier<Prompter> prompterFactory) {
        String docCount = stats.indexedDocuments()
                .stream().mapToObj(n -> n + " indexed documents")
                .findFirst().orElse("(index check skipped)");
        System.err.println("Project '" + name + "' has " + docCount
                + " and " + stats.memberCount() + " members.");
        System.err.println("This will permanently delete the project, its index, "
                + "document queues, report map, and artifact directory. "
                + "This cannot be undone.");
        Prompter prompter = prompterFactory.get();
        prompter.promptString(
                "To confirm, type the project name",
                typedName -> {
                    if (!typedName.trim().equals(name)) {
                        throw new Validators.InvalidValueException(
                                "name", "typed name does not match");
                    }
                });
        return true;
    }

    private static void emitDeleteResult(ProjectDeleted deleted,
                                         ProjectDeleteOptions options,
                                         boolean json) {
        if (json) {
            printJsonOrFallback(deleteResultMap(deleted),
                    "deleted project '" + deleted.name() + "'");
            return;
        }
        if (deleted.noop()) {
            System.out.println("project '" + deleted.name() + "' does not exist (no-op)");
            return;
        }
        String indexBadge = options.keepIndex()
                ? "index skipped"
                : (deleted.indexDeleted() ? "index OK" : "index FAILED");
        String dbBadge = deleted.dbDeleted() ? "db OK" : "db FAILED";
        String queuesBadge = deleted.queuesDeleted() ? "queues OK" : "queues FAILED";
        String reportMapBadge = deleted.reportMapDeleted() ? "report-map OK" : "report-map FAILED";
        String artifactsBadge = deleted.artifactsDeleted() ? "artifacts OK" : "artifacts skipped";
        System.out.println("deleted project '" + deleted.name() + "' ("
                + dbBadge + ", " + indexBadge + ", " + queuesBadge + ", "
                + reportMapBadge + ", " + artifactsBadge + ")");
        warnIfPartialFailure(deleted, options);
    }

    private static void warnIfPartialFailure(ProjectDeleted deleted, ProjectDeleteOptions options) {
        boolean indexFailed = !options.keepIndex() && !deleted.indexDeleted();
        if (!deleted.dbDeleted() || indexFailed
                || !deleted.queuesDeleted() || !deleted.reportMapDeleted()) {
            // Some load-bearing step failed: keep the cascade exit code 0
            // (the cascade did run to completion) but nudge the operator
            // to retry with --if-exists, which is continuation-friendly.
            System.err.println("warning: cascade completed with failures; "
                    + "re-run with --if-exists to retry");
        }
    }

    /**
     * Serializes {@code payload} to stdout, falling back to {@code fallbackLine}
     * (also on stdout) if Jackson chokes. Shared between the happy-path JSON
     * emitters that all use {@link #MAPPER} on a {@code Map} of typed values.
     */
    private static void printJsonOrFallback(Map<String, Object> payload, String fallbackLine) {
        try {
            System.out.println(MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            System.out.println(fallbackLine);
        }
    }

    /**
     * Shared shape for the delete JSON payload. Keeping a single field list
     * here means a new step in {@link ProjectDeleted} (e.g. a future
     * {@code policiesDeleted}) only has to be added in one place to flow
     * through the happy-path, no-op, and missing-with-{@code --if-exists}
     * emitters.
     */
    private static Map<String, Object> deleteResultMap(ProjectDeleted deleted) {
        return Map.ofEntries(
                Map.entry("deleted", !deleted.noop()),
                Map.entry("noop", deleted.noop()),
                Map.entry("name", deleted.name()),
                Map.entry("dbDeleted", deleted.dbDeleted()),
                Map.entry("indexDeleted", deleted.indexDeleted()),
                Map.entry("queuesDeleted", deleted.queuesDeleted()),
                Map.entry("reportMapDeleted", deleted.reportMapDeleted()),
                Map.entry("artifactsDeleted", deleted.artifactsDeleted()));
    }

    private static void emitDeleteNoop(String name, boolean json) {
        String fallback = "project '" + name + "' does not exist (no-op)";
        if (json) {
            ProjectDeleted noop = new ProjectDeleted(name, false, false, false, false, false, true);
            printJsonOrFallback(deleteResultMap(noop), fallback);
        } else {
            System.out.println(fallback);
        }
    }

    private static void emitDeleteAborted(String name, boolean json) {
        if (json) {
            // aborted != noop: aborted means the operator cancelled, noop means
            // the project did not exist. Consumers reading `noop` to learn
            // whether the project still exists must not be misled.
            Map<String, Object> payload = Map.ofEntries(
                    Map.entry("deleted", false),
                    Map.entry("noop", false),
                    Map.entry("aborted", true),
                    Map.entry("name", name));
            try {
                System.out.println(MAPPER.writeValueAsString(payload));
            } catch (Exception e) {
                System.err.println("aborted");
            }
        } else {
            System.err.println("aborted");
        }
    }

    static int handleProjectGrant(ProjectAdminService service, Properties properties) {
        String project = properties.getProperty(PROJECT_GRANT_OPT);
        String user    = properties.getProperty(PROJECT_GRANT_USER_OPT);
        String alias   = properties.getProperty(PROJECT_GRANT_ROLE_OPT);
        boolean ifNotExists = Boolean.parseBoolean(properties.getProperty(PROJECT_GRANT_IF_NOT_EXISTS_OPT));
        boolean json        = Boolean.parseBoolean(properties.getProperty(PROJECT_GRANT_JSON_OPT));
        try {
            Role role = org.icij.datashare.cli.Validators.projectRole(alias);
            ProjectGranted granted = ifNotExists
                    ? service.grantIfNotExists(project, user, role)
                    : service.grant(project, user, role);
            emitGrantResult(granted, json);
            return EXIT_SUCCESS;
        } catch (ProjectNotFoundException e) {
            return error(e.getMessage(), "not_found", EXIT_NOT_FOUND, json);
        } catch (org.icij.datashare.project.admin.UserNotFoundException e) {
            return error(e.getMessage(), "not_found", EXIT_NOT_FOUND, json);
        } catch (org.icij.datashare.project.admin.ValidationException
                 | org.icij.datashare.cli.Validators.InvalidValueException e) {
            return error(e.getMessage(), "validation", EXIT_VALIDATION, json);
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", EXIT_RUNTIME, json);
        }
    }

    private static void emitGrantResult(ProjectGranted granted, boolean json) {
        String roleShort  = stripPrefix(granted.role());
        String prevShort  = granted.previousRole() == null ? null : stripPrefix(granted.previousRole());
        if (json) {
            // LinkedHashMap (not Map.ofEntries) because previousRole may be null
            // and we want it to serialize as a JSON null, matching the contract
            // in the design spec.
            java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("project", granted.name());
            payload.put("user", granted.userLogin());
            payload.put("role", roleShort);
            payload.put("previousRole", prevShort);
            payload.put("noop", granted.noop());
            printJsonOrFallback(payload, fallbackGrantLine(granted, roleShort, prevShort));
            return;
        }
        System.out.println(fallbackGrantLine(granted, roleShort, prevShort));
    }

    private static String fallbackGrantLine(ProjectGranted granted, String roleShort, String prevShort) {
        if (granted.noop()) {
            return "'" + granted.userLogin() + "' already has " + roleShort
                    + " on '" + granted.name() + "' (no-op)";
        }
        String tail = prevShort == null ? "" : " (was " + prevShort + ")";
        return "granted " + roleShort + " on '" + granted.name()
                + "' to '" + granted.userLogin() + "'" + tail;
    }

    private static String stripPrefix(Role role) {
        String n = role.name();
        return n.startsWith("PROJECT_") ? n.substring("PROJECT_".length()) : n;
    }

    private static int error(String message, String code, int exit, boolean json) {
        if (json) {
            try {
                System.err.println(MAPPER.writeValueAsString(Map.of(
                        "error", code, "message", message)));
            } catch (Exception e) {
                System.err.println("error: " + message);
            }
        } else {
            System.err.println("error: " + message);
        }
        return exit;
    }
}
