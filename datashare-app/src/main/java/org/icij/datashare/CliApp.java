package org.icij.datashare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.cli.CliExtensionService;
import org.icij.datashare.cli.Prompter;
import org.icij.datashare.cli.Validators;
import org.icij.datashare.cli.spi.CliExtension;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.project.admin.ProjectAdminService;
import org.icij.datashare.project.admin.ProjectCreateRequest;
import org.icij.datashare.project.admin.ProjectCreated;
import org.icij.datashare.project.admin.ProjectDeleteOptions;
import org.icij.datashare.project.admin.ProjectDeleted;
import org.icij.datashare.project.admin.ProjectExistsException;
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

    // Single ObjectMapper shared across handlers for serializing JSON output.
    // Input is read from typed sibling properties, not parsed JSON, so this
    // mapper never sees untrusted strings.
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            return 0;
        } catch (UserExistsException e) {
            return error(e.getMessage(), "conflict", 4, json);
        } catch (ValidationException e) {
            return error(e.getMessage(), "validation", 5, json);
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", 1, json);
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
            return 0;
        } catch (UserNotFoundException e) {
            return error(e.getMessage(), "not_found", 3, json);
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", 1, json);
        }
    }

    // Signature asymmetry note: handleProjectCreate takes only (service, properties)
    // because ProjectCreateCommand runs all of its operator prompts in the picocli
    // layer before dispatch -- by the time we land here, every field is already in
    // the typed sibling properties. handleProjectDelete on the other hand defers
    // the typed-name confirmation to the dispatcher (it needs the index count and
    // member count from service.stats() to show in the prompt, and the service is
    // only resolved in CliApp), so it takes a Supplier<Prompter> for test injection.
    static int handleProjectCreate(ProjectAdminService service, Properties properties) {
        String name = properties.getProperty(PROJECT_CREATE_OPT);
        boolean json = Boolean.parseBoolean(properties.getProperty(PROJECT_CREATE_JSON_OPT));
        boolean ifNotExists = Boolean.parseBoolean(properties.getProperty(PROJECT_CREATE_IF_NOT_EXISTS_OPT));
        boolean noIndex = Boolean.parseBoolean(properties.getProperty(PROJECT_CREATE_NO_INDEX_OPT));
        try {
            String sourcePathOpt = properties.getProperty(PROJECT_CREATE_SOURCE_PATH_OPT);
            String creationDateOpt = properties.getProperty(PROJECT_CREATE_CREATION_DATE_OPT);
            String updateDateOpt = properties.getProperty(PROJECT_CREATE_UPDATE_DATE_OPT);
            // The CLI command validates these strings as ISO-8601 before
            // dispatch (Validators.iso8601), so parse failures here would
            // indicate a programming error rather than user input.
            Date creationDate = creationDateOpt == null ? null : Date.from(Instant.parse(creationDateOpt));
            Date updateDate = updateDateOpt == null ? null : Date.from(Instant.parse(updateDateOpt));
            ProjectCreateRequest request = new ProjectCreateRequest(
                    name,
                    properties.getProperty(PROJECT_CREATE_LABEL_OPT),
                    properties.getProperty(PROJECT_CREATE_DESCRIPTION_OPT),
                    sourcePathOpt == null ? null : Path.of(sourcePathOpt),
                    properties.getProperty(PROJECT_CREATE_ALLOW_FROM_MASK_OPT),
                    properties.getProperty(PROJECT_CREATE_SOURCE_URL_OPT),
                    properties.getProperty(PROJECT_CREATE_MAINTAINER_NAME_OPT),
                    properties.getProperty(PROJECT_CREATE_PUBLISHER_NAME_OPT),
                    properties.getProperty(PROJECT_CREATE_LOGO_URL_OPT),
                    creationDate,
                    updateDate,
                    !noIndex);

            ProjectCreated created = ifNotExists
                    ? service.createIfNotExists(request)
                    : service.create(request);

            // Auto-grant PROJECT_ADMIN to the creator. Skipped for no-op creates
            // (the project already existed; the creator's grant state is the
            // operator's concern) and for explicit absence of a creator.
            //
            // Warning policy: when --creator was set explicitly we warn on any
            // miss (the operator asked for a specific grant and we should tell
            // them it didn't happen). When the creator was resolved by falling
            // back to --defaultUserName we stay silent on a missing user --
            // typical dev setups have a custom login that doesn't match the
            // launcher's default, and the noise isn't helpful.
            String explicitCreator = properties.getProperty(PROJECT_CREATE_CREATOR_OPT);
            boolean creatorExplicit = explicitCreator != null && !explicitCreator.isBlank();
            String creator = resolveCreator(properties);
            boolean granted = false;
            String grantWarning = null;
            if (creator != null && !created.noop()) {
                try {
                    granted = service.addAdminToProject(created.name(), creator);
                    if (!granted && creatorExplicit) {
                        grantWarning = "user '" + creator
                                + "' not found in inventory; auto-grant skipped";
                    }
                } catch (Exception e) {
                    grantWarning = "failed to grant PROJECT_ADMIN on '" + created.name()
                            + "' to '" + creator + "': " + e.getMessage();
                }
                if (grantWarning != null) {
                    System.err.println("warning: " + grantWarning);
                }
            }

            if (json) {
                System.out.println(MAPPER.writeValueAsString(Map.ofEntries(
                        Map.entry("created", !created.noop()),
                        Map.entry("noop", created.noop()),
                        Map.entry("name", created.name()),
                        Map.entry("label", created.label() == null ? "" : created.label()),
                        Map.entry("description", created.description() == null ? "" : created.description()),
                        Map.entry("sourcePath", created.sourcePath() == null ? "" : created.sourcePath().toString()),
                        Map.entry("allowFromMask", created.allowFromMask() == null ? "" : created.allowFromMask()),
                        Map.entry("sourceUrl", created.sourceUrl() == null ? "" : created.sourceUrl()),
                        Map.entry("maintainerName", created.maintainerName() == null ? "" : created.maintainerName()),
                        Map.entry("publisherName", created.publisherName() == null ? "" : created.publisherName()),
                        Map.entry("logoUrl", created.logoUrl() == null ? "" : created.logoUrl()),
                        Map.entry("creationDate", created.creationDate() == null ? "" : created.creationDate().toInstant().toString()),
                        Map.entry("updateDate", created.updateDate() == null ? "" : created.updateDate().toInstant().toString()),
                        Map.entry("indexCreated", created.indexCreated()),
                        Map.entry("creator", creator == null ? "" : creator),
                        Map.entry("grantApplied", granted))));
            } else if (created.noop()) {
                System.out.println("project '" + created.name() + "' already exists (no-op)");
            } else {
                String indexBadge = created.indexCreated() ? "index=created" : "index=skipped";
                System.out.println("created project '" + created.name() + "' (label='"
                        + (created.label() == null ? "" : created.label()) + "', source-path="
                        + created.sourcePath() + ", allow-from-mask=" + created.allowFromMask()
                        + ", " + indexBadge + ")");
                if (granted) {
                    System.out.println("granted PROJECT_ADMIN on '" + created.name()
                            + "' to '" + creator + "'");
                }
            }
            return 0;
        } catch (ProjectExistsException e) {
            return error(e.getMessage(), "conflict", 4, json);
        } catch (org.icij.datashare.project.admin.ValidationException e) {
            return error(e.getMessage(), "validation", 5, json);
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", 1, json);
        }
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
            ProjectStats stats;
            try {
                stats = service.stats(name, !keepIndex);
            } catch (ProjectNotFoundException e) {
                if (ifExists) {
                    emitDeleteNoop(name, json);
                    return 0;
                }
                throw e;
            }

            if (!(yes || noInput)) {
                String docCount = stats.indexedDocuments() == ProjectStats.INDEX_CHECK_SKIPPED
                        ? "(index check skipped)"
                        : stats.indexedDocuments() + " indexed documents";
                System.err.println("Project '" + name + "' has " + docCount
                        + " and " + stats.memberCount() + " members.");
                System.err.println("This will permanently delete the project, its index, "
                        + "document queues, report map, and artifact directory. "
                        + "This cannot be undone.");
                Prompter prompter = prompterFactory.get();
                String typed = prompter.promptString(
                        "To confirm, type the project name",
                        typedName -> {
                            if (!typedName.trim().equals(name)) {
                                throw new Validators.InvalidValueException(
                                        "name", "typed name does not match");
                            }
                        });
                if (!typed.trim().equals(name)) {
                    emitDeleteAborted(name, json);
                    return 0;
                }
            }

            ProjectDeleted deleted = ifExists
                    ? service.deleteIfExists(name, options)
                    : service.delete(name, options);

            if (json) {
                System.out.println(MAPPER.writeValueAsString(deleteResultMap(deleted)));
            } else if (deleted.noop()) {
                System.out.println("project '" + deleted.name() + "' does not exist (no-op)");
            } else {
                String indexBadge = options.keepIndex()
                        ? "index skipped"
                        : (deleted.indexDeleted() ? "index OK" : "index FAILED");
                String artifactsBadge = deleted.artifactsDeleted() ? "artifacts OK" : "artifacts skipped";
                String dbBadge = deleted.dbDeleted() ? "db OK" : "db FAILED";
                String queuesBadge = deleted.queuesDeleted() ? "queues OK" : "queues FAILED";
                String reportMapBadge = deleted.reportMapDeleted() ? "report-map OK" : "report-map FAILED";
                System.out.println("deleted project '" + deleted.name() + "' ("
                        + dbBadge + ", " + indexBadge + ", " + queuesBadge + ", "
                        + reportMapBadge + ", " + artifactsBadge + ")");
                if (!deleted.dbDeleted() || (!options.keepIndex() && !deleted.indexDeleted())
                        || !deleted.queuesDeleted() || !deleted.reportMapDeleted()) {
                    // Some load-bearing step failed: keep the cascade exit code 0
                    // (the cascade did run to completion) but nudge the operator
                    // to retry with --if-exists, which is continuation-friendly.
                    System.err.println("warning: cascade completed with failures; "
                            + "re-run with --if-exists to retry");
                }
            }
            return 0;
        } catch (ProjectNotFoundException e) {
            return error(e.getMessage(), "not_found", 3, json);
        } catch (Prompter.ValidationFailedException e) {
            // Confirmation prompt exhausted retries: treat as user-initiated abort.
            emitDeleteAborted(name, json);
            return 0;
        } catch (Exception e) {
            return error("runtime: " + e.getMessage(), "runtime", 1, json);
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
        ProjectDeleted noop = new ProjectDeleted(name, false, false, false, false, false, true);
        if (json) {
            try {
                System.out.println(MAPPER.writeValueAsString(deleteResultMap(noop)));
            } catch (Exception e) {
                System.out.println("project '" + name + "' does not exist (no-op)");
            }
        } else {
            System.out.println("project '" + name + "' does not exist (no-op)");
        }
    }

    private static void emitDeleteAborted(String name, boolean json) {
        if (json) {
            try {
                // aborted != noop: aborted means the operator cancelled, noop means
                // the project did not exist. Consumers reading `noop` to learn
                // whether the project still exists must not be misled.
                System.out.println(MAPPER.writeValueAsString(Map.ofEntries(
                        Map.entry("deleted", false),
                        Map.entry("noop", false),
                        Map.entry("aborted", true),
                        Map.entry("name", name))));
            } catch (Exception e) {
                System.err.println("aborted");
            }
        } else {
            System.err.println("aborted");
        }
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
